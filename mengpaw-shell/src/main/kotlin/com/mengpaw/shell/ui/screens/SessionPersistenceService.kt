// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

// 序列化模型 → SessionPersistenceModels.kt; 序列化/磁盘 I/O/整理纯函数 → SessionPersistenceCodec.kt;
// 保存队列 → SessionSaveEngine.kt (2026-08-06, >400 行文件拆分批次4)

// ── Session persistence service ──

/**
 * Manages session persistence — save, load, history, cleanup, and session lifecycle.
 */
class SessionPersistenceService(
    private val sessions: MutableMap<String, AgentSession>,
    private val viewModelScope: CoroutineScope,
    private val getActiveAgentName: () -> String,
    private val onSwitchAgent: (String) -> Unit,
    private val onStopAgent: () -> Unit,
    private val onCreateAgent: (String, String?) -> Unit,
) {

    // ── Session history state ──

    /** A recorded chat session (persists across newSession() calls and app restarts). */
    @Serializable
    data class SessionRecord(
        val id: String,
        val title: String,
        val preview: String,
        val timestamp: Long,
        val messageCount: Int,
        val compacted: Boolean = false,
        val compactedSummary: String = "",
        val agentName: String = "",
        val framework: String? = null,     // null = local agent, non-null = remote framework name
        val archived: Boolean = false      // true = hidden from default view, can be toggled back
    ) {
        fun toJson(): String = json.encodeToString(SessionRecord.serializer(), this)

        companion object {
            fun fromJson(jsonStr: String): SessionRecord = json.decodeFromString(jsonStr)
        }
    }

    /** Sessions grouped by agent name (local + framework). */
    data class AgentSessionGroup(
        val agentName: String,
        val framework: String?,        // null = local, non-null = remote framework
        val sessions: List<SessionRecord>
    )

    private val _sessionHistory = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessionHistory: StateFlow<List<SessionRecord>> = _sessionHistory.asStateFlow()

    private val _hideCompacted = MutableStateFlow(false)
    val hideCompacted: StateFlow<Boolean> = _hideCompacted.asStateFlow()
    fun toggleHideCompacted() { _hideCompacted.value = !_hideCompacted.value }

    private val _hideArchived = MutableStateFlow(true) // default: hide archived
    val hideArchived: StateFlow<Boolean> = _hideArchived.asStateFlow()
    fun toggleHideArchived() { _hideArchived.value = !_hideArchived.value }

    // ── Per-session tracking ──

    /** Track current session ID for per-session save. Auto-assigned on first message. */
    private var currentSessionId: String = ""

    private fun ensureSessionId() {
        if (currentSessionId.isBlank()) {
            currentSessionId = "sess_${System.currentTimeMillis()}"
        }
    }

    // ── Auto-save (队列/快照/落盘 → SessionSaveEngine.kt) ──

    private val saveEngine = SessionSaveEngine(
        sessions = sessions,
        getActiveAgentName = getActiveAgentName,
        ensureSessionId = { ensureSessionId() },
        currentSessionId = { currentSessionId },
        historyHasId = { id -> _sessionHistory.value.any { it.id == id } },
        appendHistory = { record ->
            _sessionHistory.value = (_sessionHistory.value + record).takeLast(100)
            saveSessionHistoryToDisk(_sessionHistory.value)
        }
    )

    /** Persist active session messages so they survive process death. Main 仅快照, 不落盘. */
    fun saveCurrentSession() = saveEngine.saveCurrentSession()

    /** 退出前兜底: 等待队列落盘 (onCleared 调用). */
    fun flushSaveQueue() = saveEngine.flushSaveQueue()

    /** Save a specific session's messages to a per-session file. 序列化+写盘也在 worker. */
    private fun saveSessionById(sessionId: String, msgs: List<ChatMessageUi>) =
        saveEngine.saveSessionById(sessionId, msgs)

    // ── Load (读取/恢复辅助 → SessionPersistenceCodec.kt) ──

    /** Restore last session messages from disk. Returns true if restored. */
    fun restoreCurrentSession(): Boolean {
        return try {
            val file = File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
            when (val read = readCurrentSessionFile()) {
                is CurrentSessionRead.Missing -> false
                is CurrentSessionRead.Corrupt -> {
                    try { file.delete() } catch (_: Exception) {}
                    false
                }
                is CurrentSessionRead.Ok -> {
                    val msgs = read.msgs
                    val lastMsg = msgs.lastOrNull()
                    val endsWithError = lastMsg is ChatMessageUi.Agent && lastMsg.content.startsWith("执行出错")
                    if (endsWithError) {
                        try { file.delete() } catch (_: Exception) {}
                        return false
                    }
                    val (recovered, wasStuck) = recoverInterruptedMessages(msgs)
                    val session = sessions[getActiveAgentName()] ?: return false
                    session.messages.value = recovered

                    // ── Engine session restore after process death ──
                    if (msgs.isNotEmpty()) {
                        val engineMsgs = toEngineConversation(msgs)
                        val (restoredId, prevEngineId) = readEngineSessionIds()
                        val engineSessionId = restoredId ?: "sess_${System.currentTimeMillis()}"
                        try {
                            session.engine.restoreConversation(
                                externalSessionId = engineSessionId,
                                messages = engineMsgs,
                                lastWasInterrupted = wasStuck,
                                previousEngineSessionId = prevEngineId
                            )
                        } catch (_: Exception) { /* engine restore best-effort */ }
                    }

                    // Build sidebar record
                    val preview = msgs.firstOrNull()?.let {
                        when (it) {
                            is ChatMessageUi.User -> it.content.take(40)
                            is ChatMessageUi.Agent -> it.content.take(40)
                            else -> ""
                        }
                    } ?: ""
                    val sessionId = read.sessionId ?: "sess_restored"

                    val existingIndex = _sessionHistory.value.indexOfFirst { it.id == sessionId }
                    val record = SessionRecord(
                        id = sessionId, title = preview.ifBlank { "会话" }, preview = preview,
                        timestamp = file.lastModified(), messageCount = msgs.size,
                        agentName = getActiveAgentName()
                    )

                    if (existingIndex >= 0) {
                        val mutable = _sessionHistory.value.toMutableList()
                        mutable[existingIndex] = record
                        _sessionHistory.value = mutable
                    } else {
                        _sessionHistory.value = (_sessionHistory.value.filter { it.id != sessionId } + record).takeLast(100)
                    }
                    saveSessionHistoryToDisk(_sessionHistory.value)
                    currentSessionId = sessionId
                    true
                }
            }
        } catch (_: Exception) {
            try { File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json").delete() } catch (_: Exception) {}
            false
        }
    }

    // ── Session history persistence (序列化/磁盘 I/O → SessionPersistenceCodec.kt) ──

    fun loadSessionHistory() {
        _sessionHistory.value = loadSessionHistoryFromDisk()
    }

    fun cleanupOrphanSessions() {
        try {
            val sessionsDir = File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
            val before = _sessionHistory.value.size
            val cleaned = cleanupOrphanRecords(_sessionHistory.value, sessionsDir, currentSessionId)
            _sessionHistory.value = cleaned
            val removed = before - cleaned.size
            if (removed > 0) {
                saveSessionHistoryToDisk(cleaned)
                KernelLog.i("AgentViewModel", "Cleaned $removed orphan session records")
            }
        } catch (_: Exception) {}
    }

    fun dedupSessionHistory() {
        try {
            val (cleaned, removed) = dedupRecords(_sessionHistory.value)
            if (removed > 0) {
                _sessionHistory.value = cleaned
                saveSessionHistoryToDisk(cleaned)
                KernelLog.i("AgentViewModel", "Deduped $removed duplicate session records")
            }
        } catch (_: Exception) {}
    }

    // ── Session lifecycle ──

    /** Start a new session for a specific agent (switches to it if needed). */
    fun newSessionFor(agentName: String, framework: String? = null) {
        val target = if (framework != null) "$framework/$agentName" else agentName
        val currentActive = getActiveAgentName()
        // Compare against _activeAgent value indirectly
        if (currentActive != target) {
            onSwitchAgent(target)
        }
        if (!sessions.containsKey(target)) {
            onCreateAgent(agentName, framework)
        }
        newSession()
    }

    /** Auto-save current session and start a new one. */
    fun newSession() {
        onStopAgent()
        sessions[getActiveAgentName()]?.engine?.newConversation()
        val msgs = sessions[getActiveAgentName()]?.messages?.value?.filter { it !is ChatMessageUi.System } ?: emptyList()
        if (msgs.isNotEmpty()) {
            val existingCount = _sessionHistory.value.count { it.agentName == getActiveAgentName() }
            val title = "会话 #${existingCount + 1}"
            val preview = msgs.lastOrNull().let {
                when (it) {
                    is ChatMessageUi.Agent -> it.content.take(60)
                    is ChatMessageUi.User -> it.content.take(60)
                    else -> ""
                }
            }
            val currentAgent = getActiveAgentName()
            val sessId = "sess_${System.currentTimeMillis()}"
            saveSessionById(sessId, msgs)
            currentSessionId = sessId
            val session = sessions[currentAgent]
            val record = SessionRecord(
                id = sessId,
                title = title, preview = preview,
                timestamp = System.currentTimeMillis(),
                messageCount = msgs.size,
                agentName = currentAgent,
                framework = session?.framework
            )
            _sessionHistory.value = (_sessionHistory.value + record).takeLast(100)
            saveSessionHistoryToDisk(_sessionHistory.value)
        }
        // 新会话保持空列表 — 去掉"新会话已创建。"占位提示 (用户要求, 2026-08-04)
        sessions[getActiveAgentName()]?.messages?.value = emptyList()
    }

    /** Switch to a saved session, restoring its messages. */
    fun switchToSession(record: SessionRecord) {
        ensureSessionId()
        val currentMsgs = sessions[getActiveAgentName()]?.messages?.value?.filter { it !is ChatMessageUi.System } ?: emptyList()
        if (currentMsgs.isNotEmpty()) {
            saveSessionById(currentSessionId, currentMsgs)
        }
        if (record.agentName.isNotBlank() && record.agentName != getActiveAgentName()) {
            val target = if (record.framework != null) "${record.framework}/${record.agentName}" else record.agentName
            onSwitchAgent(target)
        }
        val loaded = loadSessionMessagesFromDisk(record.id)
        if (loaded.isNotEmpty()) {
            currentSessionId = record.id
            sessions[getActiveAgentName()]?.messages?.value = loaded
        } else {
            currentSessionId = ""
            sessions[getActiveAgentName()]?.messages?.value = listOf(
                ChatMessageUi.Agent("已切换到「${record.title}」，但该会话暂无已保存的消息记录。")
            )
        }
    }

    // ── Session actions ──

    /** Compact a session — keep summary, mark as read-only. */
    fun compactSession(id: String) {
        _sessionHistory.value = _sessionHistory.value.map {
            if (it.id == id) it.copy(compacted = true, compactedSummary = "已压缩: ${it.preview.take(100)}")
            else it
        }
        saveSessionHistoryToDisk(_sessionHistory.value)
    }

    /** Repair a session — fixes truncated markdown / unclosed syntax caused by abnormal interruption. */
    fun repairSession(id: String) {
        val record = _sessionHistory.value.find { it.id == id } ?: return
        val sessionKey = if (record.framework != null) "${record.framework}/${record.agentName}" else record.agentName
        val session = sessions[sessionKey] ?: return
        val msgs = session.messages.value.toMutableList()
        var changed = false
        for (i in msgs.indices) {
            val msg = msgs[i]
            if (msg is ChatMessageUi.Agent) {
                val text = repairMarkdown(msg.content)
                if (text != msg.content) { msgs[i] = ChatMessageUi.Agent(text); changed = true }
            }
        }
        if (changed) {
            session.messages.value = msgs
            _sessionHistory.value = _sessionHistory.value.map {
                if (it.id == id) it.copy(compactedSummary = "已修复: ${it.preview.take(60)}") else it
            }
            saveSessionHistoryToDisk(_sessionHistory.value)
        }
    }

    /** Delete a session record — 全量清理, 不残留任何磁盘/内存痕迹。 */
    fun deleteSession(id: String) {
        _sessionHistory.value = _sessionHistory.value.filter { it.id != id }
        saveSessionHistoryToDisk(_sessionHistory.value)
        // ① 会话消息文件 sessions/$id.json
        try {
            val file = File(com.mengpaw.kernel.DataPaths.BASE, "sessions/$id.json")
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
        // ② 内核检查点文件 会话检查点/{sessionId}_step_*.json (无 delete API, 按前缀清)
        try {
            val cpDir = File(com.mengpaw.kernel.DataPaths.CHECKPOINTS)
            if (cpDir.exists()) {
                cpDir.listFiles { f -> f.name.startsWith(id) }?.forEach { it.delete() }
            }
        } catch (_: Exception) {}
        // ③ 删除的是当前活跃会话 → 连根清除, 否则 Chat 界面残留 + 重启"复活":
        //    - 清空内存消息 (Chat 界面立即清空, 不能再继续聊)
        //    - currentSessionId 重置, 新消息开新会话
        //    - 删除 current_session.json (restoreCurrentSession 恢复的源头)
        if (currentSessionId == id) {
            currentSessionId = ""
            sessions[getActiveAgentName()]?.messages?.value = emptyList()
            try {
                val cur = File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
                if (cur.exists()) cur.delete()
            } catch (_: Exception) {}
        }
    }

    // ── Query ──

    /** Get sessions for the current agent (excluding compacted/archived if hidden). */
    fun getSessions(): List<SessionRecord> {
        val all = _sessionHistory.value.sortedByDescending { it.timestamp }
        return all.filter { showSession(it) }
    }

    private fun showSession(r: SessionRecord): Boolean {
        if (_hideCompacted.value && r.compacted) return false
        if (_hideArchived.value && r.archived) return false
        return true
    }

    /** Sessions grouped by local agents (framework == null), sorted by most recent. */
    fun getLocalAgentGroups(): List<AgentSessionGroup> {
        val all = _sessionHistory.value.filter { showSession(it) }
        return all
            .filter { it.framework == null }
            .groupBy { it.agentName.ifBlank { DEFAULT_AGENT_NAME } }
            .map { (name, sessions) -> AgentSessionGroup(name, null, sessions.sortedByDescending { it.timestamp }) }
            .sortedByDescending { it.sessions.firstOrNull()?.timestamp ?: 0L }
    }

    /** Sessions grouped by framework → agent, for the frameworks section. */
    fun getFrameworkGroups(): List<Pair<String, List<AgentSessionGroup>>> {
        val all = _sessionHistory.value.filter { showSession(it) }
        return all
            .filter { it.framework != null }
            .groupBy { it.framework!! }
            .mapValues { (_, sessions) ->
                sessions.groupBy { it.agentName.ifBlank { "Agent" } }
                    .map { (name, s) -> AgentSessionGroup(name, sessions.first().framework, s.sortedByDescending { it.timestamp }) }
                    .sortedByDescending { it.sessions.firstOrNull()?.timestamp ?: 0L }
            }
            .toList()
            .sortedByDescending { (_, groups) -> groups.maxOfOrNull { it.sessions.firstOrNull()?.timestamp ?: 0L } ?: 0L }
    }

    /** All known framework names (even those without sessions yet). */
    fun knownFrameworks(): List<String> = sessions.values
        .mapNotNull { it.framework }
        .distinct()
}
