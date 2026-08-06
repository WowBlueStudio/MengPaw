// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 部落 Kanban 看板命令组 — 从 TribePlugin 拆分。
 * 任务列表 / 详情 / 取消 / 重试 / 完成回传 / 清理 (task.list/show/cancel/retry/done + cleanup)。
 *
 * 全局依赖 (isRunning/acpServer/agentId) 读 [TribePlugin] companion;
 * 参数解析用 [parseFlags] (TribeCommandUtils)。
 */
internal class TribeKanbanCommands(
    private val kanbanBoard: TribeKanbanBoard
) {

    // ─────────────────────────────────────────────────────────────
    // Kanban 看板命令
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdTaskList(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flags = parseFlags(args)
        val statusFilter = flags.flags["status"]?.let {
            try { TaskStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
        }
        val limit = flags.flags["limit"]?.toIntOrNull() ?: 20
        val includeArchived = flags.flags.containsKey("all")

        val tasks = kanbanBoard.list(status = statusFilter, limit = limit, includeArchived = includeArchived)
        if (tasks.isEmpty()) {
            val statusHint = if (statusFilter != null) "（状态: ${statusFilter.name}）" else ""
            return ExecutionResult.ok("📋 无任务$statusHint")
        }

        val sb = StringBuilder("## 📋 看板 (${tasks.size})\n\n")
        for (t in tasks) {
            val icon = when (t.status) {
                TaskStatus.PENDING -> "⏳"; TaskStatus.ASSIGNED -> "📤"; TaskStatus.RUNNING -> "🔄"
                TaskStatus.COMPLETED -> "✅"; TaskStatus.FAILED -> "❌"; TaskStatus.TIMED_OUT -> "⏰"
                TaskStatus.CANCELLED -> "🚫"
            }
            sb.appendLine("$icon **${t.title.take(60)}** (`${t.id}`)")
            sb.appendLine("   - 状态: ${t.status.name} | 优先级: ${t.priority.name} | 来自: ${t.fromAgent}")
            sb.appendLine("   - 重试: ${t.retryCount}/${t.maxRetries} | 创建: ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(t.createdAt))}")
            sb.appendLine()
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    suspend fun cmdTaskShow(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.task.show <task-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val task = kanbanBoard.get(args[0])
            ?: return ExecutionResult.fail("任务不存在: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)

        return ExecutionResult.ok("""
## 任务详情: ${task.title}

| 字段 | 值 |
|------|-----|
| 任务ID | `${task.id}` |
| 状态 | ${task.status.name} |
| 优先级 | ${task.priority.name} (${task.priority.label}) |
| 来自 | ${task.fromAgent} → ${task.toAgent} |
| 创建 | ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(task.createdAt))} |
| 更新 | ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(task.updatedAt))} |
| 超时 | ${task.timeoutMs}ms |
| 重试 | ${task.retryCount}/${task.maxRetries} |
| 深度 | ${task.depth} |
| 父任务 | ${task.parentTaskId ?: "(无)"} |

${if (task.description.isNotEmpty()) "## 描述\n${task.description}\n" else ""}
${if (task.result != null) "## 结果\n${task.result}\n" else ""}
${if (task.errorMessage != null) "## 错误\n${task.errorMessage}\n" else ""}
        """.trimIndent())
    }

    suspend fun cmdTaskCancel(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.task.cancel <task-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return try {
            kanbanBoard.cancel(args[0])
            ExecutionResult.ok("✅ 任务 ${args[0]} 已取消。")
        } catch (e: Exception) {
            ExecutionResult.fail("取消失败: ${e.message}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
    }

    suspend fun cmdTaskRetry(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.task.retry <task-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return try {
            kanbanBoard.retry(args[0])
            ExecutionResult.ok("🔄 任务 ${args[0]} 已重置为 ASSIGNED，等待重试。")
        } catch (e: Exception) {
            ExecutionResult.fail("重试失败: ${e.message}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
    }

    /**
     * 完成任务并发回结果（嵌套委派链的响应端）。
     * 接收 Agent 完成任务后调用，结果通过 ACP RESULT 发回发起方；
     * 若本任务有父任务（嵌套委派），提示沿链回传。
     */
    suspend fun cmdTaskDone(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: tribe.task.done <task-id> <result>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val taskId = args[0]
        val result = args.drop(1).joinToString(" ")

        val task = kanbanBoard.get(taskId)
            ?: return ExecutionResult.fail("任务不存在: $taskId", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (task.status == TaskStatus.COMPLETED) return ExecutionResult.ok("任务 $taskId 已完成。")

        kanbanBoard.transition(taskId, TaskStatus.COMPLETED, result = result)
        TribeInboxWatcher.markProcessed(taskId)

        // 通过 ACP 发 RESULT 给发起方（嵌套委派链回传）
        var forwardNote = ""
        if (TribePlugin.isRunning) {
            val server = TribePlugin.acpServer
            if (server != null && task.fromAgent.isNotBlank() && task.fromAgent != TribePlugin.agentId) {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("taskId", JsonPrimitive(taskId))
                    put("result", JsonPrimitive(result))
                    put("status", JsonPrimitive("COMPLETED"))
                    if (task.parentTaskId != null) {
                        put("parentTaskId", JsonPrimitive(task.parentTaskId!!))
                        put("origin", JsonPrimitive(taskId))
                    }
                }.toString()
                val msg = com.mengpaw.kernel.acp.AcpMessage.result(TribePlugin.agentId, task.fromAgent, payload)
                // P2 修复: 空 catch 吞异常无日志 — 结果回传失败需要可审计 (forwardNote 维持不显示)
                try { server.sendViaTransport(msg); forwardNote = "，结果已发回 ${task.fromAgent}" }
                catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.taskResultForward") }
            }
        }

        // 嵌套委派提示: 父任务等待沿链回传
        val parentNote = if (task.parentTaskId != null && task.depth > 0)
            "\n\n🔗 本任务是嵌套委派（depth=${task.depth}）。父任务 `${task.parentTaskId}` 还等待结果，可用 `tribe.task.done ${task.parentTaskId} <合并后的结果>` 沿链回传。" else ""

        return ExecutionResult.ok("✅ 任务 $taskId 已完成$forwardNote。$parentNote")
    }

    // ─────────────────────────────────────────────────────────────
    // 清理
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdCleanup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flags = parseFlags(args)
        val archived = if (flags.flags.containsKey("archive")) kanbanBoard.archive() else 0
        return ExecutionResult.ok("✅ 已清理。归档完成/失败/取消任务: $archived")
    }
}
