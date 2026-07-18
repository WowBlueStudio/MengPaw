// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.incubator

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import com.mengpaw.core.DataPaths
import java.io.File

/**
 * Agent Incubator — sub-Agent lifecycle management.
 *
 * Spawns child Agents from templates, assigns roles/skills,
 * monitors their task queue, and collects results.
 *
 * Each spawned Agent gets:
 * - A directory under Agent文档/incubator/{agent-id}/
 * - Agents.md (inherited from parent's security rules)
 * - Soul.md (custom role/skills from spawn template)
 * - Profile.md (auto-generated identity)
 * - inbox/ (task queue from parent)
 */
class IncubatorPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "incubator-plugin", name = "Agent孵化器", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "子Agent孵化：创建/配置/监控/终止/结果收集",
        minCoreVersion = "0.2.0",
        commands = listOf("incubator.spawn", "incubator.list", "incubator.terminate", "incubator.inbox")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "spawn" to ::spawn, "list" to ::list, "terminate" to ::terminate, "inbox" to ::inbox
    )

    private val baseDir = File(DataPaths.INCUBATOR)

    data class ChildAgent(val id: String, val name: String, val role: String, val status: String, val tasksCompleted: Int)

    // ── incubator.spawn — 孵化子Agent ───────────────────────────

    private suspend fun spawn(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 3) return ExecutionResult.fail(
            "Usage: incubator.spawn <name> <role> <skills>", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val name = args[0]; val role = args[1]; val skills = args.drop(2).joinToString(" ")
        val id = "child-${System.currentTimeMillis().toString().takeLast(6)}"
        val agentDir = File(baseDir, id).also { it.mkdirs() }

        // Write Soul.md with role and skills
        File(agentDir, "Soul.md").writeText("""
# Agent 灵魂设定

## 身份
- 名称: $name
- ID: $id
- 角色: $role
- 父Agent: ${ctx.sessionId}

## 技能
$skills

## 执行模式
- 类型: ReAct
- 最大步数: 30
- 自主性: 执行父Agent委派的任务，完成后报告结果

## 通信
- 接收任务: inbox/ 目录
- 报告结果: 写入 Memory.md
- 团队协作: hermes.memo 共享记忆
""".trimIndent())

        // Write Profile.md
        File(agentDir, "Profile.md").writeText("""
# 关系设定
- 名称: $name
- ID: $id
- 父Agent: ${ctx.sessionId}
- 角色: $role
""".trimIndent())

        // Create inbox
        File(agentDir, "inbox").mkdirs()

        return ExecutionResult.ok("子Agent 已孵化: $name ($id)\n角色: $role\n技能: $skills\n目录: ${agentDir.absolutePath}")
    }

    // ── incubator.list ────────────────────────────────────────────

    private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val children = baseDir.listFiles()?.filter { it.isDirectory }?.map { dir ->
            val soul = File(dir, "Soul.md").let { if (it.exists()) it.readText() else "" }
            val name = Regex("名称:\\s*(.+)").find(soul)?.groupValues?.get(1)?.trim() ?: dir.name
            val role = Regex("角色:\\s*(.+)").find(soul)?.groupValues?.get(1)?.trim() ?: "未设定"
            val inbox = File(dir, "inbox").listFiles()?.size ?: 0
            val completed = File(dir, "Memory.md").let { if (it.exists()) it.readText().lines().count { l -> l.startsWith("## mem-") } else 0 }
            ChildAgent(dir.name, name, role, if (inbox > 0) "busy ($inbox pending)" else "idle", completed)
        }?.sortedBy { it.name } ?: emptyList()

        if (children.isEmpty()) return ExecutionResult.ok("(No spawned Agents)\n\n孵化: incubator.spawn <name> <role> <skills>")
        return ExecutionResult.ok("## 子Agent (${children.size})\n\n" + children.joinToString("\n") {
            "### ${it.name} (${it.id})\n- 角色: ${it.role}\n- 状态: ${it.status}\n- 已完成: ${it.tasksCompleted} tasks"
        })
    }

    // ── incubator.terminate — 终止子Agent ──────────────────────

    private suspend fun terminate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: incubator.terminate <agent-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val dir = File(baseDir, args[0])
        if (!dir.exists()) return ExecutionResult.fail("Agent not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        dir.deleteRecursively()
        return ExecutionResult.ok("子Agent ${args[0]} 已终止并清理。")
    }

    // ── incubator.inbox — 查看/向子Agent发任务 ──────────────────

    private suspend fun inbox(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: incubator.inbox <agent-id> [task-content]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val dir = File(baseDir, args[0])
        if (!dir.exists()) return ExecutionResult.fail("Agent not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val inbox = File(dir, "inbox").also { it.mkdirs() }

        if (args.size == 1) {
            val tasks = inbox.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            return if (tasks.isEmpty()) ExecutionResult.ok("(inbox empty)")
            else ExecutionResult.ok(tasks.joinToString("\n") { "• ${it.name}: ${it.readText().take(100)}" })
        }

        // Send task
        val task = args.drop(1).joinToString(" ")
        val taskFile = File(inbox, "task_${System.currentTimeMillis()}.md")
        taskFile.writeText("# 委派任务\n- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n\n$task")
        return ExecutionResult.ok("任务已发送到 ${args[0]} 的 inbox。")
    }
}
