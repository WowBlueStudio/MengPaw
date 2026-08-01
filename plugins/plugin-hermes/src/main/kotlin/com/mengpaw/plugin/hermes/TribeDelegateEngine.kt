// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Tribe 委派引擎 — 管理委派生命周期：发送、超时、指数退避重试、结果收集。
 *
 * ## 委派流程
 * ```
 * tribe.delegate called
 *   │
 *   ├── 1. 创建 TribeTask，写入 Kanban (status=ASSIGNED)
 *   ├── 2. 写入目标 inbox 保底
 *   ├── 3. 发送 ACP DELEGATE 消息（如可用）
 *   ├── 4. 启动超时监控协程
 *   │       ├── 收到 RESULT → Kanban COMPLETED → 返回
 *   │       ├── 超时 → Kanban TIMED_OUT
 *   │       │      └── canRetry? → 指数退避 → 重试 (goto step 3)
 *   │       └── maxRetries 耗尽 → Kanban FAILED → 返回错误
 *   └── 5. 返回 ExecutionResult
 * ```
 */
class TribeDelegateEngine(
    private val kanbanBoard: TribeKanbanBoard,
    private val acpServer: AcpServer?,
    private val scope: CoroutineScope
) {
    /** 正在等待结果的任务（taskId → deferred）。 */
    private val pendingResults = mutableMapOf<String, CompletableDeferred<String>>()
    /** 正在重试的任务（taskId → retry job）。 */
    private val retryJobs = mutableMapOf<String, Job>()
    /** 正在执行的超时监控任务。 */
    private var monitorJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    // ── Public API ─────────────────────────────────────────────

    /**
     * 委派任务。同步返回 ExecutionResult，内部异步等待结果。
     * @param task 要委派的 TribeTask
     * @param targetAgentId 目标 Agent ID（目录名）
     * @param targetName 目标 Agent 显示名
     * @return 任务结果或超时/错误描述
     */
    suspend fun delegate(
        task: TribeTask,
        targetAgentId: String,
        targetName: String
    ): ExecutionResult {
        // Step 1: 写入 Kanban
        val created = kanbanBoard.create(task)
        val deferred = CompletableDeferred<String>()
        pendingResults[created.id] = deferred

        // Step 2: 写入目标 inbox 保底
        writeToInbox(targetAgentId, created)

        // Step 3: 发送 ACP DELEGATE（如可用）
        sendDelegate(created, targetAgentId)

        // Step 4: 等待结果（带超时）
        try {
            val result = kotlinx.coroutines.withTimeout(created.timeoutMs) {
                deferred.await()
            }
            pendingResults.remove(created.id)
            kanbanBoard.transition(created.id, TaskStatus.COMPLETED, result = result)
            return ExecutionResult.ok("任务完成: ${created.title}\n\n$result")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingResults.remove(created.id)
            return handleTimeout(created, targetAgentId, targetName)
        }
    }

    /**
     * 由 [TribeAcpHandler] 在收到 RESULT 消息时调用。
     * 唤醒等待中的 delegate() 调用。
     */
    suspend fun onResultReceived(taskId: String, result: String) {
        val deferred = pendingResults[taskId]
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(result)
        }
    }

    /** 启动后台超时监控（启动时调用）。 */
    fun start() {
        monitorJob = scope.launch {
            while (isActive) {
                // 定期清理超时的 pendingResults（兜底）
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    /** 停止所有后台协程。 */
    fun stop() {
        monitorJob?.cancel()
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        pendingResults.clear()
    }

    // ── 内部方法 ──────────────────────────────────────────────

    private fun sendDelegate(task: TribeTask, targetAgentId: String) {
        if (task.delegateMode == DelegateMode.FILE) return  // 仅文件模式
        val server = acpServer ?: return  // ACP 不可用
        if (task.delegateMode == DelegateMode.ACP || task.delegateMode == DelegateMode.AUTO) {
            scope.launch {
                try {
                    val payload = buildJsonObject {
                        put("taskId", task.id)
                        put("title", task.title)
                        put("description", task.description)
                        put("priority", task.priority.name)
                        put("replyTo", task.fromAgent)
                        put("timeoutMs", task.timeoutMs)
                        put("depth", task.depth)
                        put("parentTaskId", task.parentTaskId ?: "")
                    }.toString()
                    val msg = AcpMessage.delegate(task.fromAgent, targetAgentId, payload)
                    server.sendViaTransport(msg)
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TribeDelegateEngine.sendDelegate")
                }
            }
        }
    }

    private fun writeToInbox(targetAgentId: String, task: TribeTask) {
        try {
            val inboxDir = File(DataPaths.AGENTS, "$targetAgentId/inbox").also { it.mkdirs() }
            val taskFile = File(inboxDir, "tribe_task_${System.currentTimeMillis()}.md")
            // 原子写 (tmp + rename) — 防崩溃留下半写文件被收件箱 watcher 误读
            val tmp = File(inboxDir, "${taskFile.name}.tmp")
            tmp.writeText("""
# 部落委派任务
- 来自: ${task.fromAgent}
- 任务ID: ${task.id}
- 优先级: ${task.priority.label}
- 方式: ${task.delegateMode.label}
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}

## 任务
${task.title}
${if (task.description.isNotEmpty()) "\n$task.description" else ""}

## 响应方式
完成后通过 `tribe.memo` 通知，或等待 ACP RESULT 自动收集。
""".trimIndent())
            tmp.renameTo(taskFile)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeDelegateEngine.writeToInbox")
        }
    }

    private suspend fun handleTimeout(
        task: TribeTask,
        targetAgentId: String,
        targetName: String
    ): ExecutionResult {
        // 更新 Kanban 状态
        val timedOut = kanbanBoard.transition(task.id, TaskStatus.TIMED_OUT,
            error = "Timeout after ${task.timeoutMs}ms")

        if (!timedOut.canRetry()) {
            return ExecutionResult.fail(
                "任务超时: ${task.title}。已重试 ${timedOut.retryCount}/${task.maxRetries} 次。\n" +
                "建议: `tribe.status` 查看对端在线状态, `tribe.task.list` 查看任务详情, `tribe.task.retry <id>` 手动重试。",
                errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_TIMEOUT)
        }

        // 指数退避
        val delayMs = timedOut.nextRetryDelayMs()
        val retryJob = scope.launch {
            delay(delayMs)
            if (!isActive) return@launch
            val retried = kanbanBoard.retry(task.id)

            // 重新写入 inbox
            writeToInbox(targetAgentId, retried)

            // 重新发送 ACP
            sendDelegate(retried, targetAgentId)

            // 重新等待
            val newDeferred = CompletableDeferred<String>()
            pendingResults[retried.id] = newDeferred
            try {
                val result = kotlinx.coroutines.withTimeout(retried.timeoutMs) {
                    newDeferred.await()
                }
                pendingResults.remove(retried.id)
                kanbanBoard.transition(retried.id, TaskStatus.COMPLETED, result = result)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                pendingResults.remove(retried.id)
                handleTimeout(retried, targetAgentId, targetName)
            }
        }
        retryJobs[task.id] = retryJob

        return ExecutionResult.ok("任务已发送（第 ${timedOut.retryCount + 1}/${task.maxRetries} 次尝试），等待 ${delayMs / 1000} 秒后重试。")
    }
}
