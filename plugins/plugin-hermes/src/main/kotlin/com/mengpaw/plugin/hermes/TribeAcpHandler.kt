// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpHandler
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Tribe ACP 协议处理器 — 处理 4 种消息类型的 ACP 消息。
 *
 * ## 消息路由
 *
 * | 类型 | 入口 | 行为 |
 * |------|------|------|
 * | DELEGATE | [onDelegate] | 解析 JSON → Kanban 创建任务 → inbox 保底 |
 * | RESULT | [onResult] | 解析 taskId+result → Kanban 更新 COMPLETED → 通知 DelegateEngine |
 * | SHARE_MEMORY | [onShareMemory] | 写入 TEAM_MEMOS/memo_acp_{ts}.md |
 * | HEARTBEAT | [onHeartbeat] | 更新心跳监控的对端时间戳 |
 */
class TribeAcpHandler(
    private val localAgentName: String,
    private val kanbanBoard: TribeKanbanBoard?,
    private val delegateEngine: TribeDelegateEngine?,
    private val heartbeatMonitor: TribeHeartbeatMonitor?
) : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(
        AcpMessageType.DELEGATE,
        AcpMessageType.RESULT,
        AcpMessageType.SHARE_MEMORY,
        AcpMessageType.HEARTBEAT,
        AcpMessageType.TRIBE_CHAT
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        return try {
            val type = AcpMessageType.valueOf(message.type)
            when (type) {
                AcpMessageType.DELEGATE -> onDelegate(message, server)
                AcpMessageType.RESULT -> onResult(message, server)
                AcpMessageType.SHARE_MEMORY -> onShareMemory(message)
                AcpMessageType.HEARTBEAT -> onHeartbeat(message)
                AcpMessageType.TRIBE_CHAT -> onTribeChat(message)
                else -> null
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeAcpHandler.handle(${message.type})")
            AcpResult(false, "handler_error", e.message ?: "unknown error")
        }
    }

    // ── TRIBE_CHAT ──────────────────────────────────────────────

    /** 接收部落广播消息，写入团队共享收件箱。 */
    private suspend fun onTribeChat(message: AcpMessage): AcpResult {
        val text = try {
            json.parseToJsonElement(message.payload).jsonObject["message"]?.jsonPrimitive?.content
        } catch (_: Exception) { null } ?: message.payload
        return try {
            val inbox = File(DataPaths.TEAM_INBOX).also { it.mkdirs() }
            val chatFile = File(inbox, "chat_${System.currentTimeMillis()}.md")
            chatFile.writeText("""
# 部落广播
- 来自: ${message.from}
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}

$text
""".trimIndent())
            AcpResult(true, "chat_received")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeAcpHandler.onTribeChat")
            AcpResult(false, "write_failed", e.message ?: "inbox write error")
        }
    }

    // ── DELEGATE ───────────────────────────────────────────────

    /**
     * 接收远程委派。payload 应为结构化 JSON：
     * ```json
     * {
     *   "taskId": "a1b2c3d4",
     *   "title": "...",
     *   "description": "...",
     *   "priority": "P0",
     *   "replyTo": "sender-agent-id",
     *   "timeoutMs": 120000,
     *   "depth": 0,
     *   "parentTaskId": null
     * }
     * ```
     * 若 payload 不是合法 JSON（兼容旧版纯文本），返回 null 让内核 DelegateHandler 处理。
     */
    private suspend fun onDelegate(message: AcpMessage, server: AcpServer): AcpResult? {
        val payload = message.payload.ifBlank { return null }

        val obj = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { return null }

        val title = obj["title"]?.jsonPrimitive?.content
            ?: obj["task"]?.jsonPrimitive?.content
            ?: return null  // 不是 Tribe 格式，让内核 handler 处理

        val priority = try {
            obj["priority"]?.jsonPrimitive?.content?.let { TaskPriority.valueOf(it) }
        } catch (_: Exception) { null } ?: TaskPriority.P1

        val timeoutMs = (obj["timeoutMs"]?.jsonPrimitive?.intOrNull?.toLong()) ?: 120_000L
        val depth = obj["depth"]?.jsonPrimitive?.intOrNull ?: 0
        val parentTaskId = obj["parentTaskId"]?.jsonPrimitive?.let { if (it.isString) it.content else null }
        val description = obj["description"]?.jsonPrimitive?.let { if (it.isString) it.content else "" } ?: ""

        // 创建本地 Kanban 任务
        val task = TribeTask(
            title = title.take(200),
            description = description,
            priority = priority,
            status = TaskStatus.RUNNING,
            fromAgent = message.from,
            toAgent = localAgentName,
            timeoutMs = timeoutMs,
            depth = depth,
            parentTaskId = parentTaskId,
            delegateMode = DelegateMode.ACP
        )

        // 写入 Kanban (create 强制 PENDING; P0 fix: 立即转 ASSIGNED — 否则后续
        // RESULT 分支 COMPLETED/FAILED 转换必抛 IllegalArgumentException)
        val created = kanbanBoard?.let { board ->
            try { board.transition(board.create(task).id, TaskStatus.ASSIGNED) }
            catch (e: Exception) { task }
        } ?: task

        // 写入接收方自己的 inbox 保底（bug fix: 之前误写发送方目录）
        try {
            val inbox = File(DataPaths.AGENTS, "$localAgentName/inbox").also { it.mkdirs() }
            val taskFile = File(inbox, "tribe_delegate_${System.currentTimeMillis()}.md")
            taskFile.writeText("""
# 部落委派任务
- 来自: ${message.from}
- 任务ID: ${created.id}
- 优先级: ${priority.label}
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}

## 任务
$title
${if (description.isNotEmpty()) "\n$description" else ""}

## 响应方式
完成后请通过 tribe.memo 通知，或等待对端通过 ACP RESULT 收集。
""".trimIndent())
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeAcpHandler.onDelegate.inbox")
        }

        return AcpResult(true, "delegate_queued", created.id)
    }

    // ── RESULT ─────────────────────────────────────────────────

    /**
     * 接收任务结果。payload 格式：
     * ```json
     * {
     *   "taskId": "a1b2c3d4",
     *   "result": "## Summary\n...",
     *   "status": "COMPLETED",
     *   "error": null
     * }
     * ```
     */
    private suspend fun onResult(message: AcpMessage, server: AcpServer): AcpResult? {
        val payload = message.payload.ifBlank { return null }
        val obj = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { return null }

        val taskId = obj["taskId"]?.jsonPrimitive?.content ?: return null
        val resultText = obj["result"]?.jsonPrimitive?.let { if (it.isString) it.content else null }
        val errorText = obj["error"]?.jsonPrimitive?.let { if (it.isString) it.content else null }
        val statusStr = obj["status"]?.jsonPrimitive?.let { if (it.isString) it.content else null }

        if (kanbanBoard == null) return AcpResult(false, "kanban_unavailable")

        when (statusStr) {
            "COMPLETED" -> kanbanBoard.transition(taskId, TaskStatus.COMPLETED, result = resultText)
            "FAILED" -> kanbanBoard.transition(taskId, TaskStatus.FAILED, error = errorText ?: resultText)
            else -> return AcpResult(false, "unknown_status", statusStr ?: "null")
        }

        // 通知 delegateEngine 释放等待
        delegateEngine?.onResultReceived(taskId, resultText ?: errorText ?: "")

        // ── 嵌套委派结果回传链 ──
        // 若本任务有父任务（嵌套委派），自动将结果沿链转寄给父任务发起方，
        // 让 A→B→C 的链式委派在 C 完成后自动回传至 B 再至 A。
        val completed = kanbanBoard.get(taskId)
        val parentTaskId = completed?.parentTaskId
        val parentFrom = completed?.fromAgent
        if (parentTaskId != null && !parentFrom.isNullOrBlank() && parentFrom != localAgentName) {
            try {
                val forward = kotlinx.serialization.json.buildJsonObject {
                    put("taskId", JsonPrimitive(parentTaskId))
                    put("result", JsonPrimitive(resultText ?: errorText ?: ""))
                    put("status", JsonPrimitive(statusStr ?: "COMPLETED"))
                    put("origin", JsonPrimitive(taskId))
                    put("originResult", JsonPrimitive(resultText ?: errorText ?: ""))
                }.toString()
                val msg = AcpMessage.result(localAgentName, parentFrom, forward)
                server.sendViaTransport(msg)
            } catch (e: Exception) {
                ErrorCollector.report(e, "TribeAcpHandler.onResult.forward")
            }
        }

        return AcpResult(true, "result_received", taskId)
    }

    // ── SHARE_MEMORY ───────────────────────────────────────────

    /**
     * 接收共享记忆。写入 `TEAM_MEMOS/memo_acp_{ts}.md`。
     */
    private suspend fun onShareMemory(message: AcpMessage): AcpResult {
        val content = message.payload.ifBlank { "(empty)" }
        val memosDir = File(DataPaths.TEAM_MEMOS).also { it.mkdirs() }
        val memoFile = File(memosDir, "memo_acp_${System.currentTimeMillis()}.md")
        return try {
            memoFile.writeText("""
# 团队共享记忆 (ACP)
- 来自: ${message.from}
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}
- 来源: ACP 远程共享

$content
""".trimIndent())
            AcpResult(true, "memory_shared")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeAcpHandler.onShareMemory")
            AcpResult(false, "write_failed", e.message ?: "inbox write error")
        }
    }

    // ── HEARTBEAT ──────────────────────────────────────────────

    /** 更新心跳监控中对应对端的最近活跃时间。 */
    private suspend fun onHeartbeat(message: AcpMessage): AcpResult {
        heartbeatMonitor?.onHeartbeat(message.from)
        return AcpResult(true, "alive")
    }
}
