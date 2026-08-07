// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * 部落团队命令组 — 从 TribePlugin 拆分。
 * 团队管理 / Agent 发现 / 提问 / 角色设定 (team/discover/ask/role)。
 *
 * 依赖通过构造参数注入; 文件系统辅助 (teamDir) 与成员发现由 [TribePlugin] 提供。
 */
internal class TribeTeamCommands(
    private val heartbeatMonitor: TribeHeartbeatMonitor,
    private val discoverMembers: () -> List<TeamMember>,
    private val teamDir: () -> File
) {

    // ─────────────────────────────────────────────────────────────
    // 团队管理
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdTeam(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val members = discoverMembers()
        if (args.isEmpty()) {
            if (members.isEmpty()) return ExecutionResult.ok("当前无团队成员。使用 `tribe.discover` 发现其他 Agent 并用 `tribe.team invite` 邀请。")
            return ExecutionResult.ok("## 团队 (${members.size} 成员)\n\n" + members.joinToString("\n") { m ->
                "### ${m.name}\n- 角色: ${m.role}\n- ID: ${m.id}\n- 状态: ${m.status}\n- 擅长: ${m.skills}"
            })
        }
        if (args[0] == "invite" && args.size >= 3) {
            val id = args[1]; val r = args.drop(2).joinToString(" ")
            val file = File(teamDir(), "$id.md")
            return try {
                file.writeText("name: $id\nrole: $r\njoined: ${System.currentTimeMillis()}\nstatus: active")
                // 注册到心跳监控
                heartbeatMonitor.registerPeer(id, id)
                ExecutionResult.ok("✅ 已邀请 Agent $id 加入团队，角色: $r")
            } catch (e: Exception) {
                ErrorCollector.report(e, "TribePlugin.team")
                ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
            }
        }
        if (args[0] == "remove" && args.size >= 2) {
            File(teamDir(), "${args[1]}.md").delete()
            return ExecutionResult.ok("已将 ${args[1]} 移出团队")
        }
        return ExecutionResult.fail("Usage: tribe.team [invite <id> <role>|remove <id>]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    // ─────────────────────────────────────────────────────────────
    // 发现
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdDiscover(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // --lan: 自动组队 — 从 FrameworkPlugin 发现的局域网框架同步成员
        if (args.contains("--lan")) {
            val force = args.contains("--force")
            val added = TribeLanDiscovery.syncFromLan(force = force)
            added.forEach { heartbeatMonitor.registerPeer(it, it) }
            if (added.isEmpty()) {
                return ExecutionResult.ok("LAN 自动组队完成，无新增成员（全部已存在，用 --force 覆盖）。")
            }
            return ExecutionResult.ok("🌐 LAN 自动组队：已将 ${added.size} 个 Agent 加入团队:\n" +
                added.joinToString("\n") { "• $it" })
        }

        // 默认: 扫描本地 Agent 目录 (仅真 Agent 工作区 — 统一判定, v0.34.x)
        val agentsDir = File(DataPaths.AGENTS)
        val dirs = try {
            agentsDir.listFiles()?.filter { it.isDirectory && DataPaths.isAgentWorkspaceDir(it.name) }?.sortedBy { it.name } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val discovered = dirs.map { dir ->
            val profile = File(dir, "Profile.md").let {
                if (it.exists()) try { it.readText() } catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.discover"); "(读取失败)" } else "(无档案)"
            }
            val name = Regex("名称:\\s*(.+)").find(profile)?.groupValues?.get(1)?.trim() ?: dir.name
            val role = Regex("角色:\\s*(.+)").find(profile)?.groupValues?.get(1)?.trim() ?: "未设定"
            "• $name (ID: ${dir.name}) — 角色: $role"
        }
        val hint = if (discovered.isEmpty())
            "未发现其他 Agent。创建新 Agent: 在 Agent文档/ 下新建目录并写入 Profile.md。"
        else "## 发现的 Agent (${discovered.size})\n\n${discovered.joinToString("\n")}"
        return ExecutionResult.ok("$hint\n\n> 💡 使用 `tribe.discover --lan` 自动将局域网框架成员加入团队。")
    }

    // ─────────────────────────────────────────────────────────────
    // 提问
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdAsk(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: tribe.ask <agent-name> <question>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val target = args[0]; val question = args.drop(1).joinToString(" ")
        val members = discoverMembers()
        val member = members.find { it.name == target || it.id == target }
            ?: return ExecutionResult.fail("Agent '$target' 不在团队中。", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val inboxDir = File(DataPaths.AGENTS, "${member.id}/inbox").also { it.mkdirs() }
        val qFile = File(inboxDir, "ask_${System.currentTimeMillis()}.md")
        return try {
            qFile.writeText("""
# 提问
- 来自: ${ctx.sessionId}
- 问题: $question
""".trimIndent())
            ExecutionResult.ok("✅ 已向 ${member.name} 提问。等待对方通过 tribe.memo 回复。")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.ask")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 角色
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdRole(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.role <role-description>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val roleDesc = args.joinToString(" ")
        val profile = File(DataPaths.AGENTS, "${ctx.sessionId.take(8)}/Profile.md")
        val current = if (profile.exists()) try { profile.readText() } catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.role"); "" } else ""
        val updated = if (current.contains("角色:"))
            current.replace(Regex("角色:\\s*.+"), "角色: $roleDesc")
        else current + "\n角色: $roleDesc"
        profile.parentFile?.mkdirs()
        return try {
            profile.writeText(updated)
            ExecutionResult.ok("✅ 角色已设定: $roleDesc")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.role")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }
}
