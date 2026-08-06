// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 部落委派命令组 — 从 TribePlugin 拆分。
 * 任务委派 / LLM 路由 / 任务模板 / Fleet 并行 (delegate/route/template/fleet)。
 *
 * 依赖通过构造参数注入; 全局依赖 (llmProvider/isRunning/acpServer)
 * 读 [TribePlugin] companion。参数解析用 [parseFlags] (TribeCommandUtils)。
 */
internal class TribeDelegationCommands(
    private val kanbanBoard: TribeKanbanBoard,
    private val delegateEngine: TribeDelegateEngine,
    private val fleetEngine: TribeFleetEngine,
    private val discoverMembers: () -> List<TeamMember>
) {

    // ─────────────────────────────────────────────────────────────
    // 委派
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdDelegate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.delegate <agent-name> <task> [--priority P0|P1|P2] [--timeout ms] [--mode file|acp|auto] [--template <name>] [--route]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)

        // 解析参数
        val parsed = parseFlags(args)
        val posArgs = parsed.positional
        val flags = parsed.flags
        if (posArgs.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.delegate <agent-name> <task> [--priority P0|P1|P2]", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val priority = try { flags["priority"]?.let { TaskPriority.valueOf(it.uppercase()) } } catch (_: Exception) { null } ?: TaskPriority.P1
        val timeoutMs = flags["timeout"]?.toLongOrNull() ?: 120_000L
        val mode = try { flags["mode"]?.let { DelegateMode.valueOf(it.uppercase()) } } catch (_: Exception) { null } ?: DelegateMode.AUTO

        val members = discoverMembers()

        // --route: 用 LLM 自动选择目标 Agent（此时 posArgs 全是任务描述）
        val useRoute = flags.containsKey("route")
        val target: String
        var taskDesc: String
        if (useRoute) {
            if (members.isEmpty()) return ExecutionResult.fail("团队为空，先用 tribe.discover --lan 自动组队。", errorCode = ErrorCodes.ERR_NOT_FOUND)
            val llm = TribePlugin.llmProvider ?: return ExecutionResult.fail("LLM 未配置，无法路由。请先在设置中配置 API Key。", errorCode = ErrorCodes.ERR_INTERNAL)
            taskDesc = posArgs.joinToString(" ")
            val routeResult = TribeRouter.route(taskDesc, members, kanbanBoard.snapshotStatuses(), llm)
            target = routeResult.agent
            if (routeResult.agent.isBlank()) return ExecutionResult.fail("路由失败: ${routeResult.reason}", errorCode = ErrorCodes.ERR_INTERNAL)
        } else {
            if (posArgs.size < 2) return ExecutionResult.fail(
                "Usage: tribe.delegate <agent-name> <task> [--priority P0|P1|P2]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            target = posArgs[0]
            taskDesc = posArgs.drop(1).joinToString(" ")
        }

        // --template: 用预置模板包装任务描述
        val templateName = flags["template"]
        if (templateName != null) {
            val rendered = TribeTemplates.render(templateName, taskDesc, flags)
                ?: return ExecutionResult.fail("未知模板: $templateName。可用模板:\n${TribeTemplates.describe()}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            taskDesc = rendered
        }

        // --context [maxChars]: 附加裁剪后的对话上下文（特性 10）
        val contextFlag = flags["context"]
        if (contextFlag != null) {
            val maxChars = contextFlag.toIntOrNull() ?: 2000
            val trim = TribeContextTrim.trimContext(ctx.sessionId.take(8), maxChars)
            if (trim.text.isNotBlank()) {
                taskDesc += TribeContextTrim.formatForTask(trim)
            }
        }

        val member = members.find { it.name == target || it.id == target }
            ?: return ExecutionResult.fail("Agent '$target' 不在团队中。先用 tribe.discover 发现并 tribe.team invite 邀请。",
                errorCode = ErrorCodes.ERR_NOT_FOUND)

        // --parent <taskId>: 嵌套委派链（特性 7）— 深度限制 + 环形检测
        var parentTaskId: String? = null
        var depth = 0
        val parentFlag = flags["parent"]
        if (parentFlag != null) {
            val parent = kanbanBoard.get(parentFlag)
                ?: return ExecutionResult.fail("父任务不存在: $parentFlag", errorCode = ErrorCodes.ERR_NOT_FOUND)
            if (parent.depth >= 3) return ExecutionResult.fail(
                "嵌套委派最多 3 层（父任务 depth=${parent.depth} 已达上限）。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            // 环形检测: 沿父链检查目标是否已在链路中出现
            var cursor: TribeTask? = parent
            val chainAgents = mutableSetOf(cursor!!.fromAgent, cursor.toAgent)
            while (cursor?.parentTaskId != null) {
                cursor = kanbanBoard.get(cursor.parentTaskId!!)
                if (cursor != null) {
                    chainAgents.add(cursor.fromAgent)
                    chainAgents.add(cursor.toAgent)
                }
            }
            if (member.id in chainAgents || member.name in chainAgents) {
                return ExecutionResult.fail(
                    "检测到委派环: Agent '$target' 已在当前委派链中（A→B→A 不允许）。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
            parentTaskId = parent.id
            depth = parent.depth + 1
        }

        // 创建 TribeTask
        val task = TribeTask(
            title = taskDesc.take(200),
            description = taskDesc,
            priority = priority,
            fromAgent = ctx.sessionId.take(8),
            toAgent = member.id,
            timeoutMs = timeoutMs,
            delegateMode = mode,
            parentTaskId = parentTaskId,
            depth = depth
        )

        // 通过 DelegateEngine 委派
        if (TribePlugin.isRunning && TribePlugin.acpServer != null && mode != DelegateMode.FILE) {
            return delegateEngine.delegate(task, member.id, member.name)
        }

        // 文件模式回退
        val inboxDir = File(DataPaths.AGENTS, "${member.id}/inbox").also { it.mkdirs() }
        val taskFile = File(inboxDir, "task_${System.currentTimeMillis()}.md")
        return try {
            taskFile.writeText("""
# 委派任务
- 来自: ${ctx.sessionId}
- 任务ID: ${task.id}
- 优先级: ${priority.label}
- 时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}

## 任务
$taskDesc

## 响应方式
完成后将结果写入 memory/ 目录（中期记忆）并通过 tribe.memo 通知。
""".trimIndent())
            ExecutionResult.ok("✅ 任务已委派给 ${member.name}（文件模式）。任务ID: ${task.id}")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.delegate")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 任务模板
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdTemplate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.ok("## 部落任务模板\n\n" + TribeTemplates.describe() + "\n\n用法: `tribe.delegate <agent> --template <name> <内容>`")
        }
        val t = TribeTemplates.all.find { it.name == args[0] }
            ?: return ExecutionResult.fail("未知模板: ${args[0]}。可用:\n${TribeTemplates.describe()}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok("### ${t.name} — ${t.desc}\n\n${t.skeleton}")
    }

    // ─────────────────────────────────────────────────────────────
    // LLM 能力路由
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdRoute(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.route <task-description>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val taskDesc = args.joinToString(" ")
        val members = discoverMembers()
        if (members.isEmpty()) return ExecutionResult.fail(
            "团队为空。先用 `tribe.discover --lan` 自动组队，或用 `tribe.team invite <id> <role>` 邀请。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val llm = TribePlugin.llmProvider ?: return ExecutionResult.fail(
            "LLM 未配置，无法路由。请先在设置中配置 API Key。", errorCode = ErrorCodes.ERR_INTERNAL)

        val result = TribeRouter.route(taskDesc, members, kanbanBoard.snapshotStatuses(), llm)
        if (result.agent.isBlank()) return ExecutionResult.fail("路由失败: ${result.reason}", errorCode = ErrorCodes.ERR_INTERNAL)
        val member = members.find { it.id == result.agent }
        return ExecutionResult.ok("🎯 推荐: **${member?.name ?: result.agent}**（置信度 ${(result.confidence * 100).toInt()}%）\n\n理由: ${result.reason}\n\n可直接执行: `tribe.delegate ${member?.name ?: result.agent} --route <任务>`")
    }

    // ─────────────────────────────────────────────────────────────
    // Fleet 并行执行
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdFleet(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.fleet <task>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val task = args.joinToString(" ")
        val members = discoverMembers()
        if (members.isEmpty()) return ExecutionResult.fail(
            "团队为空。先用 `tribe.discover --lan` 自动组队。", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val report = fleetEngine.run(task, members, TribePlugin.llmProvider)
        return ExecutionResult.ok(report)
    }
}
