// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * Hermes 多Agent协作插件 — 参考 Hermes 多Agent合作机制。
 *
 * 核心概念:
 * - Team: Agent 团队，每个 Agent 有角色和职责
 * - Discover: Agent 互相发现（通过 ACP 或本地目录）
 * - Delegate: 委派任务给其他 Agent
 * - Ask: 向其他 Agent 提问
 * - Memo: 团队共享记忆
 * - Role: 设定自身角色
 *
 * Hermes 参考机制:
 * - Agent 注册到团队目录 (Agent文档/team/)
 * - 委派基于角色匹配 (Role-based Routing)
 * - 共享记忆通过 Memory.md 交换
 * - ACP 用于跨进程通信，本地文件用于同进程通信
 */
class HermesPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "hermes-plugin", name = "Hermes 多Agent协作", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "多Agent对话协作：团队组建、任务委派、共享记忆、角色管理",
        minCoreVersion = "0.2.0",
        commands = listOf("hermes.team", "hermes.discover", "hermes.delegate", "hermes.ask", "hermes.memo", "hermes.role")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "team" to ::team, "discover" to ::discover, "delegate" to ::delegate,
        "ask" to ::ask, "memo" to ::memo, "role" to ::role
    )

    private val teamDir: File get() = File(DataPaths.TEAM).also { it.mkdirs() }

    // ── hermes.team — 查看/管理团队 ──────────────────────────────

    private suspend fun team(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val members = discoverTeamMembers()
        if (args.isEmpty()) {
            if (members.isEmpty()) return ExecutionResult.ok("当前无团队成员。使用 hermes.discover 发现其他 Agent。")
            return ExecutionResult.ok("## 团队 (${members.size} 成员)\n\n" + members.joinToString("\n") { m ->
                "### ${m.name}\n- 角色: ${m.role}\n- ID: ${m.id}\n- 状态: ${m.status}\n- 擅长: ${m.skills}"
            })
        }
        // hermes.team invite <agent-id> <role>
        if (args[0] == "invite" && args.size >= 3) {
            val id = args[1]; val r = args.drop(2).joinToString(" ")
            val file = File(teamDir, "$id.md")
            return try {
                file.writeText("name: $id\nrole: $r\njoined: ${System.currentTimeMillis()}\nstatus: active")
                ExecutionResult.ok("已邀请 Agent $id 加入团队，角色: $r")
            } catch (e: Exception) {
                ErrorCollector.report(e, "HermesPlugin.team")
                ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
            }
        }
        if (args[0] == "remove" && args.size >= 2) {
            File(teamDir, "${args[1]}.md").delete()
            return ExecutionResult.ok("已将 ${args[1]} 移出团队")
        }
        return ExecutionResult.fail("Usage: hermes.team [invite <id> <role>|remove <id>]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    // ── hermes.discover — 发现其他 Agent ─────────────────────────

    private suspend fun discover(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agentsDir = File(DataPaths.AGENTS)
        val dirs = agentsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val discovered = dirs.filter { it.name != "team" }.map { dir ->
            val profile = File(dir, "Profile.md").let { if (it.exists()) try { it.readText() } catch (e: Exception) { ErrorCollector.report(e, "HermesPlugin.discover"); "(读取失败)" } else "(无档案)" }
            val name = Regex("名称:\\s*(.+)").find(profile)?.groupValues?.get(1)?.trim() ?: dir.name
            val role = Regex("角色:\\s*(.+)").find(profile)?.groupValues?.get(1)?.trim() ?: "未设定"
            "• $name (ID: ${dir.name}) — 角色: $role"
        }
        if (discovered.isEmpty()) return ExecutionResult.ok("未发现其他 Agent。创建新 Agent: 在 Agent文档/ 下新建目录并写入 Profile.md。")
        return ExecutionResult.ok("## 发现的 Agent (${discovered.size})\n\n${discovered.joinToString("\n")}")
    }

    // ── hermes.delegate — 委派任务 ───────────────────────────────

    private suspend fun delegate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: hermes.delegate <agent-name> <task>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val target = args[0]; val task = args.drop(1).joinToString(" ")
        val members = discoverTeamMembers()
        val member = members.find { it.name == target || it.id == target }
            ?: return ExecutionResult.fail("Agent '$target' 不在团队中。先用 hermes.discover 发现并 hermes.team invite 邀请。",
                errorCode = ErrorCodes.ERR_NOT_FOUND)

        // Write delegation task to target agent's inbox
        val inboxDir = File(DataPaths.AGENTS, "${member.id}/inbox").also { it.mkdirs() }
        val taskFile = File(inboxDir, "task_${System.currentTimeMillis()}.md")
        return try {
            taskFile.writeText("""
# 委派任务
- 来自: ${ctx.sessionId}
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}
- 委派给: ${member.name} (${member.role})

## 任务
$task

## 响应方式
完成后将结果写入 Memory.md 并通过 hermes.memo 通知。
""".trimIndent())
            ExecutionResult.ok("任务已委派给 ${member.name}。任务文件: ${taskFile.name}")
        } catch (e: Exception) {
            ErrorCollector.report(e, "HermesPlugin.delegate")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ── hermes.ask — 向其他 Agent 提问 ───────────────────────────

    private suspend fun ask(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: hermes.ask <agent-name> <question>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val target = args[0]; val question = args.drop(1).joinToString(" ")
        val members = discoverTeamMembers()
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
            ExecutionResult.ok("已向 ${member.name} 提问。等待对方通过 hermes.memo 回复。")
        } catch (e: Exception) {
            ErrorCollector.report(e, "HermesPlugin.ask")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ── hermes.memo — 团队共享记忆 ──────────────────────────────

    private suspend fun memo(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            val memos = File(DataPaths.TEAM_MEMOS).also { it.mkdirs() }
                .listFiles()?.filter { it.extension == "md" }?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (memos.isEmpty()) return ExecutionResult.ok("(无团队共享记忆)")
            return ExecutionResult.ok(memos.take(10).joinToString("\n---\n") { try { it.readText().take(300) } catch (e: Exception) { ErrorCollector.report(e, "HermesPlugin.memo"); "(read error)" } })
        }
        val content = args.joinToString(" ")
        val memoFile = File(DataPaths.TEAM_MEMOS, "memo_${System.currentTimeMillis()}.md")
        memoFile.parentFile?.mkdirs()
        return try {
            memoFile.writeText("# 团队共享记忆\n- 作者: ${ctx.sessionId}\n- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n\n$content")
            ExecutionResult.ok("共享记忆已发布。")
        } catch (e: Exception) {
            ErrorCollector.report(e, "HermesPlugin.memo")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ── hermes.role — 设定自身角色 ───────────────────────────────

    private suspend fun role(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: hermes.role <role-description>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val roleDesc = args.joinToString(" ")
        val profile = File(DataPaths.AGENTS, "${ctx.sessionId.take(8)}/Profile.md")
        val current = if (profile.exists()) try { profile.readText() } catch (e: Exception) { ErrorCollector.report(e, "HermesPlugin.role"); "" } else ""
        val updated = if (current.contains("角色:"))
            current.replace(Regex("角色:\\s*.+"), "角色: $roleDesc")
        else current + "\n角色: $roleDesc"
        profile.parentFile?.mkdirs()
        return try {
            profile.writeText(updated)
            ExecutionResult.ok("角色已设定: $roleDesc")
        } catch (e: Exception) {
            ErrorCollector.report(e, "HermesPlugin.role")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    data class TeamMember(val id: String, val name: String, val role: String, val status: String, val skills: String)

    private fun discoverTeamMembers(): List<TeamMember> {
        return teamDir.listFiles()?.filter { it.extension == "md" }?.map { file ->
            val text = try { file.readText() } catch (e: Exception) { ErrorCollector.report(e, "HermesPlugin.discoverTeamMembers"); "" }
            TeamMember(
                id = file.nameWithoutExtension,
                name = Regex("name:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: file.nameWithoutExtension,
                role = Regex("role:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "未设定",
                status = Regex("status:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "active",
                skills = Regex("skills:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "通用"
            )
        }?.sortedBy { it.name } ?: emptyList()
    }
}
