// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.AgentTrace
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

// ── JSON serialization helpers for persistence ──

internal val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
data class SessionPersistenceData(
    val sessionId: String = "",
    val engineSessionId: String = "",
    val messages: List<MessageData> = emptyList()
)

@Serializable
data class MessageData(
    val type: String,
    val text: String,
    val executionMode: String? = null,
    val agentRef: String? = null,
    val traces: List<TraceData> = emptyList(),
    /** True for failed "!command" results (type="command"). Default false keeps old files compatible. */
    val isError: Boolean = false
)

@Serializable
data class TraceData(
    val step: Int = 0,
    val thought: String = "",
    val action: String = "",
    val observation: String = ""
)

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

    /** 增量持久化: 追踪上次落盘的消息数, 30s 保存时若未变化则跳过 I/O */
    private var lastPersistedMsgCount: Int = 0

    /** Track current session ID for per-session save. Auto-assigned on first message. */
    private var currentSessionId: String = ""

    private fun ensureSessionId() {
        if (currentSessionId.isBlank()) {
            currentSessionId = "sess_${System.currentTimeMillis()}"
        }
    }

    /** JSON file path for session persistence. */
    private val sessionHistoryFile: File
        get() = File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")

    // ── Auto-save ──

    // ── Save ──

    /** Persist active session messages so they survive process death. */
    fun saveCurrentSession() {
        try {
            val session = sessions[getActiveAgentName()] ?: return
            val msgs = session.messages.value.filter { it !is ChatMessageUi.System }
            if (msgs.isEmpty()) return
            if (msgs.size == lastPersistedMsgCount) return
            ensureSessionId()
            val messagesData = messagesToJson(msgs)
            val wrapper = SessionPersistenceData(
                sessionId = currentSessionId,
                engineSessionId = session.engine.currentConversationId() ?: "",
                messages = messagesData
            )
            val file = File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
            atomicWriteJson(file, json.encodeToString(SessionPersistenceData.serializer(), wrapper))
            saveSessionById(currentSessionId, msgs)
            lastPersistedMsgCount = msgs.size
            if (_sessionHistory.value.none { it.id == currentSessionId }) {
                val title = msgs.firstOrNull()?.let {
                    when (it) { is ChatMessageUi.User -> it.content.take(40); else -> "" }
                } ?: "会话"
                val record = SessionRecord(
                    id = currentSessionId,
                    title = if (title.isNotBlank()) title else "会话",
                    preview = msgs.lastOrNull()?.let {
                        when (it) { is ChatMessageUi.Agent -> it.content.take(60); is ChatMessageUi.User -> it.content.take(60); else -> "" }
                    } ?: "",
                    timestamp = System.currentTimeMillis(),
                    messageCount = msgs.size,
                    agentName = getActiveAgentName(),
                    framework = session.framework
                )
                _sessionHistory.value = (_sessionHistory.value + record).takeLast(100)
                saveSessionHistory()
            }
        } catch (e: Exception) { KernelLog.w("AgentVM", "updateHistory: ${e.message}") }
    }

    /** Save a specific session's messages to a per-session file. */
    private fun saveSessionById(sessionId: String, msgs: List<ChatMessageUi>) {
        try {
            val nonSystem = msgs.filter { it !is ChatMessageUi.System }
            if (nonSystem.isEmpty()) return
            val dir = File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
            dir.mkdirs()
            val file = File(dir, "$sessionId.json")
            val messagesData = messagesToJson(nonSystem)
            atomicWriteJson(file, json.encodeToString(ListSerializer(MessageData.serializer()), messagesData))
        } catch (e: Exception) { KernelLog.w("AgentVM", "saveSessionById: ${e.message}") }
    }

    // ── Load ──

    /** Load a session's messages from its per-session file. */
    private fun loadSessionMessages(sessionId: String): List<ChatMessageUi> {
        try {
            val file = File(com.mengpaw.kernel.DataPaths.BASE, "sessions/$sessionId.json")
            if (!file.exists()) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            return jsonToMessages(text)
        } catch (_: Exception) { return emptyList() }
    }

    /** Restore last session messages from disk. Returns true if restored. */
    fun restoreCurrentSession(): Boolean {
        return try {
            val file = File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
            if (!file.exists()) return false
            val text = file.readText()
            if (text.isBlank()) return false

            var restoredId: String? = null
            val arrText: String = try {
                val wrapper = json.decodeFromString<SessionPersistenceData>(text)
                restoredId = wrapper.sessionId.takeIf { it.isNotBlank() && it != "null" }
                json.encodeToString(ListSerializer(MessageData.serializer()), wrapper.messages)
            } catch (_: Exception) {
                text
            }

            val msgs = jsonToMessages(arrText)
            if (msgs.isNotEmpty()) {
                val lastMsg = msgs.lastOrNull()
                val endsWithError = lastMsg is ChatMessageUi.Agent && lastMsg.content.startsWith("⚠️ 执行出错")
                if (endsWithError) {
                    try { file.delete() } catch (_: Exception) {}
                    return false
                }
                val recovered = msgs.toMutableList()
                var wasStuck = false
                for (i in recovered.indices) {
                    val m = recovered[i]
                    if (m is ChatMessageUi.AgentWithTrace && m.isRunning) {
                        recovered[i] = ChatMessageUi.Agent(
                            "⚠️ 智能体生成被打断，请回复指令以继续。",
                            executionMode = m.executionMode,
                            agentRef = m.agentRef
                        )
                        wasStuck = true
                    }
                }
                if (wasStuck) {
                    recovered.add(ChatMessageUi.System("⚠️ 上次会话异常中断，已自动恢复。"))
                }
                val session = sessions[getActiveAgentName()] ?: return false
                session.messages.value = recovered

                // ── Engine session restore after process death ──
                if (msgs.isNotEmpty()) {
                    val engineMsgs = msgs.mapNotNull { msg ->
                        when (msg) {
                            is ChatMessageUi.User -> "user" to msg.content
                            is ChatMessageUi.Agent -> "assistant" to msg.content
                            is ChatMessageUi.AgentWithTrace -> {
                                if (msg.isRunning) null
                                else "assistant" to msg.finalContent
                            }
                            else -> null
                        }
                    }
                    val restoredId = try {
                        json.decodeFromString<SessionPersistenceData>(file.readText()).sessionId.takeIf { it.isNotBlank() && it != "null" }
                    } catch (_: Exception) { null }
                    val prevEngineId = try {
                        json.decodeFromString<SessionPersistenceData>(file.readText()).engineSessionId.takeIf { it.isNotBlank() }
                    } catch (_: Exception) { null }
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
                val sessionId = restoredId ?: "sess_restored"

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
                saveSessionHistory()
                currentSessionId = sessionId
            }
            msgs.isNotEmpty()
        } catch (_: Exception) {
            try { File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json").delete() } catch (_: Exception) {}
            false
        }
    }

    // ── Serialization helpers ──

    private fun messagesToJson(msgs: List<ChatMessageUi>): List<MessageData> {
        return msgs.mapNotNull { msg ->
            when (msg) {
                is ChatMessageUi.User -> MessageData(type = "user", text = msg.content)
                is ChatMessageUi.Agent -> MessageData(
                    type = "agent", text = msg.content,
                    executionMode = msg.executionMode, agentRef = msg.agentRef
                )
                is ChatMessageUi.AgentWithTrace -> MessageData(
                    type = "agent_trace", text = msg.finalContent,
                    traces = msg.traces.map { t ->
                        TraceData(step = t.step, thought = t.thought,
                            action = t.action ?: "", observation = t.observation ?: "")
                    },
                    executionMode = msg.executionMode, agentRef = msg.agentRef
                )
                is ChatMessageUi.CommandResult -> MessageData(
                    type = "command", text = msg.content, isError = msg.isError
                )
                else -> null
            }
        }
    }

    private fun jsonToMessages(jsonStr: String): List<ChatMessageUi> {
        return try {
            val dataList: List<MessageData> = json.decodeFromString(jsonStr)
            dataList.mapNotNull { data ->
                if (data.text.isBlank()) return@mapNotNull null
                when (data.type) {
                    "user" -> ChatMessageUi.User(data.text)
                    "agent" -> ChatMessageUi.Agent(data.text,
                        executionMode = data.executionMode?.ifEmpty { null },
                        agentRef = data.agentRef?.ifEmpty { null })
                    "agent_trace" -> {
                        val traces = data.traces.map { t ->
                            AgentTrace(t.step, t.thought,
                                t.action.ifEmpty { null },
                                t.observation.ifEmpty { null })
                        }
                        ChatMessageUi.AgentWithTrace(data.text, traces, isRunning = false,
                            executionMode = data.executionMode?.ifEmpty { null },
                            agentRef = data.agentRef?.ifEmpty { null })
                    }
                    "command" -> ChatMessageUi.CommandResult(data.text, isError = data.isError)
                    else -> null
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun atomicWriteJson(file: File, jsonStr: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(jsonStr)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            KernelLog.w("AgentVM", "atomicWriteJson: ${e.message}")
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    // ── Session history persistence ──

    fun loadSessionHistory() {
        try {
            val file = sessionHistoryFile
            if (file.exists()) {
                val text = file.readText()
                if (text.isNotBlank()) {
                    _sessionHistory.value = json.decodeFromString<List<SessionRecord>>(text)
                }
            }
        } catch (e: Exception) {
            KernelLog.w("AgentViewModel", "Corrupted session_history.json, resetting: ${e.message}")
            try { sessionHistoryFile.delete() } catch (_: Exception) {}
        }
    }

    private fun saveSessionHistory() {
        try {
            val file = sessionHistoryFile
            if (file.exists() && file.length() > 0) {
                val bak = File(file.parentFile, "${file.name}.bak")
                try { file.copyTo(bak, overwrite = true) } catch (_: Exception) {}
            }
            val jsonStr = json.encodeToString(ListSerializer(SessionRecord.serializer()), _sessionHistory.value)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(jsonStr)
            tmp.renameTo(file)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
        } catch (e: Exception) {
            KernelLog.w("AgentViewModel", "Failed to save session history: ${e.message}")
        }
    }

    fun cleanupOrphanSessions() {
        try {
            val sessionsDir = File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
            val before = _sessionHistory.value.size
            _sessionHistory.value = _sessionHistory.value.filter { record ->
                val sessionFile = File(sessionsDir, "${record.id}.json")
                if (!sessionFile.exists() && record.id != currentSessionId) return@filter false
                if (record.messageCount <= 0 && record.id != currentSessionId) return@filter false
                true
            }
            val removed = before - _sessionHistory.value.size
            if (removed > 0) {
                saveSessionHistory()
                KernelLog.i("AgentViewModel", "Cleaned $removed orphan session records")
            }
        } catch (_: Exception) {}
    }

    fun dedupSessionHistory() {
        try {
            val records = _sessionHistory.value.toMutableList()
            val seen = mutableMapOf<String, SessionRecord>()
            val toRemove = mutableSetOf<String>()
            for (record in records.sortedByDescending { it.timestamp }) {
                val key = "${record.agentName}|${record.title}"
                val existing = seen[key]
                if (existing != null) {
                    if (record.timestamp >= existing.timestamp) {
                        toRemove.add(existing.id)
                        seen[key] = record
                    } else {
                        toRemove.add(record.id)
                    }
                } else {
                    seen[key] = record
                }
            }
            if (toRemove.isNotEmpty()) {
                _sessionHistory.value = records.filter { it.id !in toRemove }
                saveSessionHistory()
                KernelLog.i("AgentViewModel", "Deduped ${toRemove.size} duplicate session records")
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
            saveSessionHistory()
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
        val loaded = loadSessionMessages(record.id)
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
        saveSessionHistory()
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
                var text = msg.content
                val fenceCount = text.count { it == '`' } / 3
                if (fenceCount % 2 != 0) {
                    text = text.trimEnd() + "\n```"
                    changed = true
                }
                val boldCount = text.split("**").size - 1
                if (boldCount % 2 != 0) {
                    text = text.trimEnd() + "**"
                    changed = true
                }
                val italicCount = text.replace("**", "").count { it == '*' }
                if (italicCount % 2 != 0) {
                    text = text.trimEnd() + "*"
                    changed = true
                }
                if (changed) msgs[i] = ChatMessageUi.Agent(text)
            }
        }
        if (changed) {
            session.messages.value = msgs
            _sessionHistory.value = _sessionHistory.value.map {
                if (it.id == id) it.copy(compactedSummary = "已修复: ${it.preview.take(60)}") else it
            }
            saveSessionHistory()
        }
    }

    /** Delete a session record — 全量清理, 不残留任何磁盘/内存痕迹。 */
    fun deleteSession(id: String) {
        _sessionHistory.value = _sessionHistory.value.filter { it.id != id }
        saveSessionHistory()
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
            lastPersistedMsgCount = 0
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
            .groupBy { it.agentName.ifBlank { "MengPaw" } }
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
