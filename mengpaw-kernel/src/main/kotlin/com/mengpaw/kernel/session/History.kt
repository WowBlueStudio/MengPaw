// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.llm.AttachmentPayload
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages Agent sessions and conversation history.
 *
 * v0.17: QwenPaw-style structured compaction with no-data-loss archive.
 * Compressed raw messages are saved to dialog/YYYY-MM-DD.jsonl before compaction.
 * The compact_summary includes a path reference so Agent can recall full history.
 *
 * v0.32.x (400 行文件拆分批次 1): 重职责已拆出到同包委托 —
 *   [SessionCompressor] (压缩/保留策略/归档/后台预压缩/自动摘要落地中期记忆)
 *   [SessionEventLog]   (会话事件 JSONL 持久化 + 内存总线广播)
 *   [SessionIntegrity]  (中断恢复 + 完整性检查/修复)
 * 公开 API 签名不变; 所有委托与 [SessionManager] 共用 this 锁, 竞态语义不变。
 */
class SessionManager {

    /** Agent name for archive paths. Set by AgentEngine. */
    @Volatile var agentName: String = "agent"
    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    // ── 职责委托 (锁共用 this — 见类注释) ──────────────────────────────

    private lateinit var compressor: SessionCompressor
    private lateinit var eventLog: SessionEventLog
    private lateinit var integrity: SessionIntegrity

    init {
        compressor = SessionCompressor(
            lock = this,
            sessionProvider = { id -> _sessions.value[id] },
            activeSessionIdProvider = { _activeSessionId.value },
            updateSession = { id, s -> _sessions.value = _sessions.value + (id to s) },
            agentNameProvider = { agentName }
        )
        eventLog = SessionEventLog(
            lock = this,
            dialogDirProvider = { DataPaths.dialogArchiveDir(agentName) },
            agentNameProvider = { agentName }
        )
        integrity = SessionIntegrity(
            lock = this,
            sessionProvider = { id -> _sessions.value[id] },
            updateSession = { id, s -> _sessions.value = _sessions.value + (id to s) }
        )
    }

    /**
     * Create a new session for a given task.
     *
     * @param scope the lifecycle scope: "agent" (default), "framework", "system", "swarm"
     * @param agentId the agent handling this session (defaults to [agentName])
     *
     * Synchronized: parallel workers (swarm mode) create sessions concurrently;
     * the CAS-style map update would otherwise lose sessions silently.
     */
    @Synchronized
    fun createSession(
        task: String,
        metadata: Map<String, String> = emptyMap(),
        scope: String = "agent",
        agentId: String? = null,
        /** false = 不抢占 activeSessionId（零待命并行 worker 用 — 防折叠压缩错会话）。 */
        activate: Boolean = true
    ): Session {
        val session = Session(
            id = UUID.randomUUID().toString().take(8),
            task = task,
            scope = scope,
            agentId = agentId ?: agentName,
            metadata = metadata
        )
        _sessions.value = _sessions.value + (session.id to session)
        if (activate) _activeSessionId.value = session.id
        // Emit lifecycle event (matching OpenClaw "created" event kind)
        eventLog.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
            kind = SessionEventBus.EventKind.SESSION_CREATED,
            sessionId = session.id,
            agentName = agentName,
            summary = task.take(120)
        ))
        return session
    }

    /**
     * Get a session by ID.
     */
    fun getSession(id: String): Session? = _sessions.value[id]

    /**
     * Add a message to the active session.
     * Synchronized to prevent concurrent add/compress race conditions.
     */
    @Synchronized
    fun addMessage(sessionId: String, message: Message): Boolean {
        val maxHistory = 200
        val session = _sessions.value[sessionId] ?: return false
        session.messages.add(message)
            if (session.messages.size > maxHistory) {
                session.messages.removeAt(0)
            }
        _sessions.value = _sessions.value + (sessionId to session)
        return true
    }

    /**
     * Replace in-place messages matching [predicate] with [transform]'s result.
     * 与 addMessage/compressIfNeeded 同一监视器 — snipStaleToolResults 等
     * 就地改写不得绕过锁与并行 worker 的 addMessage/后台预压缩竞态。
     * @return 实际替换条数
     */
    @Synchronized
    fun replaceMessages(
        sessionId: String,
        predicate: (Message) -> Boolean,
        transform: (Message) -> Message
    ): Int {
        val session = _sessions.value[sessionId] ?: return 0
        var count = 0
        val list = session.messages
        for (i in list.indices) {
            val msg = list[i]
            if (predicate(msg)) {
                list[i] = transform(msg)
                count++
            }
        }
        if (count > 0) {
            _sessions.value = _sessions.value + (sessionId to session)
        }
        return count
    }

    /**
     * Get the entire conversation for a session.
     */
    fun getHistory(sessionId: String): List<Message> {
        return _sessions.value[sessionId]?.messages?.toList() ?: emptyList()
    }

    /**
     * Compress conversation history if it exceeds the message budget.
     * 实现见 [SessionCompressor.compressIfNeeded]。
     */
    suspend fun compressIfNeeded(llmProvider: LlmProvider, maxMessages: Int = 50, specificSessionId: String? = null): Boolean =
        compressor.compressIfNeeded(llmProvider, maxMessages, specificSessionId)

    /** 消息数 ≥ threshold-margin 且无在途压缩时, 在 [scope] 后台启动压缩. µs 级返回. */
    fun scheduleCompressionIfNeeded(
        sessionId: String,
        scope: CoroutineScope,
        llmProvider: LlmProvider,
        threshold: Int = 50,
        margin: Int = 8
    ) = compressor.scheduleCompressionIfNeeded(sessionId, scope, llmProvider, threshold, margin)

    /** 关键路径兜底: 在途压缩不 join (本轮放行); 无在途且仍超阈值时同步压缩. */
    suspend fun awaitCompressionIfNeeded(
        llmProvider: LlmProvider,
        threshold: Int = 50,
        sessionId: String
    ): Boolean = compressor.awaitCompressionIfNeeded(llmProvider, threshold, sessionId)

    /** 会话生命周期事件: JSONL 持久化 + 内存总线广播. 实现见 [SessionEventLog.recordSessionEvent]. */
    @Synchronized
    fun recordSessionEvent(sessionId: String, event: SessionEventBus.SessionEvent) =
        eventLog.recordSessionEvent(sessionId, event)

    /** 列出 seq 之后的事件 (1 基序). 实现见 [SessionEventLog.listEventsSince]. */
    fun listEventsSince(sessionId: String, afterSeq: Int = 0, limit: Int = 50): List<SessionEventBus.SessionEvent> =
        eventLog.listEventsSince(sessionId, afterSeq, limit)

    /** 裁剪过期事件日志 (30 天 / 5000 行策略). 实现见 [SessionEventLog.pruneSessionEvents]. */
    fun pruneSessionEvents(sessionId: String, maxAgeDays: Int = 30, maxLines: Int = 5000) =
        eventLog.pruneSessionEvents(sessionId, maxAgeDays, maxLines)

    /**
     * Record an interrupted assistant turn as a LocalOnly message.
     * 实现见 [SessionIntegrity.recordInterruptedTurn]。
     */
    @Synchronized
    fun recordInterruptedTurn(
        sessionId: String,
        completedTools: List<InterruptedToolSummary>,
        interruptedTools: List<String>,
        hasPartialText: Boolean,
        hasPartialReasoning: Boolean
    ) = integrity.recordInterruptedTurn(
        sessionId, completedTools, interruptedTools, hasPartialText, hasPartialReasoning
    )

    /** Check whether the given session has a pending (un-consumed) interrupted turn recovery. */
    fun hasPendingRecovery(sessionId: String): Boolean = integrity.hasPendingRecovery(sessionId)

    /** Consume the pending interrupted turn recovery. 实现见 [SessionIntegrity.consumePendingRecovery]. */
    @Synchronized
    fun consumePendingRecovery(sessionId: String): Boolean = integrity.consumePendingRecovery(sessionId)

    /** Verify session data integrity. 实现见 [SessionIntegrity.checkSessionIntegrity]. */
    fun checkSessionIntegrity(sessionId: String): Boolean = integrity.checkSessionIntegrity(sessionId)

    /** Repair minor session integrity issues. 实现见 [SessionIntegrity.repairSessionIntegrity]. */
    @Synchronized
    fun repairSessionIntegrity(sessionId: String): Boolean = integrity.repairSessionIntegrity(sessionId)

    /**
     * Get the structured conversation history as a list of role/content maps.
     * Used for prefix-cache-optimized LLM requests where messages[0] is the system prompt.
     *
     * ⚠️ Filters out [Message.localOnly] messages — recovery metadata must never reach the LLM.
     *
     * 附件二进制挂载策略 (v0.32.1+ 重发成本修复): **仅最后一条带附件的 user 消息**
     * 挂 `_image`/`_audio_data` 二进制键 — 历史消息若每轮全量 base64 重发,
     * 请求体轻易击穿上下文窗口 (2MB 图 ≈ 50 万 token/step, 10 step 一轮 ≈ 500 万 token)。
     * 更早消息的视觉认知依赖 LLM 文本转述 (content 内已有 `[图片附件] 📎 path` 标注,
     * 且每轮回答都含图的内容描述), 视觉上下文损失有限; 需要重看图时新对话补发即可。
     */
    fun getStructuredHistory(sessionId: String): List<Map<String, String>> {
        val messages = _sessions.value[sessionId]?.messages ?: return emptyList()
        val filtered = messages.filter { !it.localOnly }
        val lastAttachmentUser = filtered.lastOrNull { it.role == "user" && it.attachments.isNotEmpty() }
        return filtered.map { msg ->
            val base = mapOf("role" to msg.role, "content" to msg.content)
            if (msg === lastAttachmentUser) {
                AttachmentPayload.attachBinary(base, msg.attachments)
            } else base
        }
    }

    // ── Schema Migration (matching OpenClaw schema migration pattern) ──

    /**
     * Migrate a session from its current schema version to [targetVersion].
     *
     * Each version step is implemented as a separate function:
     *   v1 → v2: (placeholder)
     *
     * When adding a new field to [Session] or [Message] that changes serialization,
     * add a new migration step here and increment [Session.schemaVersion]'s default.
     * Old persisted sessions will be migrated on next load.
     */
    internal fun migrateSession(session: Session, targetVersion: Int = 1): Session {
        var s = session
        while (s.schemaVersion < targetVersion) {
            s = when (s.schemaVersion) {
                1 -> s  // v1 → v2 placeholder: add migration logic here
                else -> { s }  // unknown version — stop
            }.also { migrated ->
                // Preserve existing messages and metadata through migration
                s.copy(
                    schemaVersion = s.schemaVersion,
                    messages = s.messages,
                    metadata = s.metadata
                )
            }
        }
        return s
    }

    /**
     * Clear all sessions.
     */
    fun clear() {
        _sessions.value = emptyMap()
        _activeSessionId.value = null
    }

    /**
     * Delete a specific session.
     */
    /** Synchronized: parallel swarm workers delete sessions concurrently (same CAS-race as createSession). */
    @Synchronized
    fun deleteSession(id: String) {
        _sessions.value = _sessions.value - id
        if (_activeSessionId.value == id) {
            _activeSessionId.value = _sessions.value.keys.firstOrNull()
        }
    }
}
