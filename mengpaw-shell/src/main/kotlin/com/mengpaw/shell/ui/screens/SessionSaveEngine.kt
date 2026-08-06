// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

// ── 会话保存引擎 — 拆自 SessionPersistenceService.kt (2026-08-06, >400 行文件拆分批次4) ──
// 落盘/序列化全在 worker 线程 (单线程执行器 FIFO), Main 只做快照。
// currentSessionId 与 _sessionHistory 仍归 Service 持有, 经 lambda 桥接 (纯机械拆分)。

internal class SessionSaveEngine(
    private val sessions: MutableMap<String, AgentSession>,
    private val getActiveAgentName: () -> String,
    private val ensureSessionId: () -> Unit,
    private val currentSessionId: () -> String,
    private val historyHasId: (String) -> Boolean,
    private val appendHistory: (SessionPersistenceService.SessionRecord) -> Unit
) {

    /** 落盘快照: Main 线程捕获(不可变 List 引用), worker 线程序列化+写盘. */
    private data class SaveSnapshot(
        val msgs: List<ChatMessageUi>,
        val engineSessionId: String,
        val sessionId: String,
        val agentName: String,
        val framework: String?
    )

    /** 单线程执行器 — 序列化+文件写全移出 Main; FIFO 后写覆盖前写 (v0.28.6). */
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-save").apply { isDaemon = false }
    }

    /** 在途快照合并: 同一会话多次保存只留最新 (队列深度恒 ≤1). */
    @Volatile private var pendingSave: SaveSnapshot? = null

    /** Persist active session messages so they survive process death. Main 仅快照, 不落盘. */
    fun saveCurrentSession() {
        try {
            val session = sessions[getActiveAgentName()] ?: return
            val msgs = session.messages.value.filter { it !is ChatMessageUi.System }
            if (msgs.isEmpty()) return
            ensureSessionId()
            val snapshot = SaveSnapshot(
                msgs = msgs,
                engineSessionId = session.engine.currentConversationId() ?: "",
                sessionId = currentSessionId(),
                agentName = getActiveAgentName(),
                framework = session.framework
            )
            pendingSave = snapshot
            try {
                saveExecutor.execute { flushPendingSave() }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // onCleared 已 shutdown — 最后快照直接同步兜底
                pendingSave = null
                doSave(snapshot)
            }
        } catch (e: Exception) { KernelLog.w("AgentVM", "saveCurrentSession: ${e.message}") }
    }

    /** worker 线程: 取最新快照落盘 (合并写入, 不重复写). */
    private fun flushPendingSave() {
        val s = pendingSave ?: return
        pendingSave = null
        doSave(s)
    }

    /** worker 线程: 序列化 + 原子写 + 会话历史更新 (单线程串行, .tmp 无并发). */
    private fun doSave(s: SaveSnapshot) {
        try {
            val messagesData = messagesToJson(s.msgs)
            // 仅当前会话快照写 current_session.json / 追加历史记录 —
            // 已删除/已切换会话的迟到快照跳过 (防止"复活"幽灵会话)
            val isCurrent = s.sessionId == currentSessionId()
            if (isCurrent) {
                val wrapper = SessionPersistenceData(
                    sessionId = s.sessionId,
                    engineSessionId = s.engineSessionId,
                    messages = messagesData
                )
                val file = File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
                atomicWriteJson(file, json.encodeToString(SessionPersistenceData.serializer(), wrapper))
                if (!historyHasId(s.sessionId)) {
                    val title = s.msgs.firstOrNull()?.let {
                        when (it) { is ChatMessageUi.User -> it.content.take(40); else -> "" }
                    } ?: "会话"
                    val record = SessionPersistenceService.SessionRecord(
                        id = s.sessionId,
                        title = if (title.isNotBlank()) title else "会话",
                        preview = s.msgs.lastOrNull()?.let {
                            when (it) { is ChatMessageUi.Agent -> it.content.take(60); is ChatMessageUi.User -> it.content.take(60); else -> "" }
                        } ?: "",
                        timestamp = System.currentTimeMillis(),
                        messageCount = s.msgs.size,
                        agentName = s.agentName,
                        framework = s.framework
                    )
                    appendHistory(record)
                }
                // 归档写入 (切换/删除后不再写 — 防止幽灵文件)
                val dir = File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
                dir.mkdirs()
                atomicWriteJson(File(dir, "${s.sessionId}.json"),
                    json.encodeToString(ListSerializer(MessageData.serializer()), messagesData))
            }
        } catch (e: Exception) { KernelLog.w("AgentVM", "updateHistory: ${e.message}") }
    }

    /** 退出前兜底: 等待队列落盘 (onCleared 调用). */
    fun flushSaveQueue() {
        try {
            saveExecutor.shutdown()
            saveExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    /** Save a specific session's messages to a per-session file. 序列化+写盘也在 worker. */
    fun saveSessionById(sessionId: String, msgs: List<ChatMessageUi>) {
        val nonSystem = msgs.filter { it !is ChatMessageUi.System }
        if (nonSystem.isEmpty()) return
        try {
            saveExecutor.execute {
                try {
                    val dir = File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
                    dir.mkdirs()
                    val messagesData = messagesToJson(nonSystem)
                    atomicWriteJson(File(dir, "$sessionId.json"),
                        json.encodeToString(ListSerializer(MessageData.serializer()), messagesData))
                } catch (e: Exception) { KernelLog.w("AgentVM", "saveSessionById: ${e.message}") }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // onCleared 后不再落盘 (snapshot 已由 flush 兜底)
        }
    }
}
