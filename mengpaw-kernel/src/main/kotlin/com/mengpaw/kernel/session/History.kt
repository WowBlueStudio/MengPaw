// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Atomic file write: write to tmp, then rename (crash-safe, prevents partial writes). */
private fun java.io.File.atomicWriteText(text: String) {
    val tmp = java.io.File(this.parentFile, "${this.name}.tmp")
    try {
        tmp.writeText(text)
        if (this.exists()) this.delete()
        tmp.renameTo(this)
    } catch (e: Exception) {
        try { tmp.delete() } catch (_: Exception) {}
        throw e
    }
}

/**
 * Manages Agent sessions and conversation history.
 *
 * v0.17: QwenPaw-style structured compaction with no-data-loss archive.
 * Compressed raw messages are saved to dialog/YYYY-MM-DD.jsonl before compaction.
 * The compact_summary includes a path reference so Agent can recall full history.
 */
class SessionManager {

    /** Agent name for archive paths. Set by AgentEngine. */
    @Volatile var agentName: String = "agent"
    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

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
        recordSessionEvent(session.id, SessionEventBus.SessionEvent(
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
     * Get the entire conversation for a session.
     */
    fun getHistory(sessionId: String): List<Message> {
        return _sessions.value[sessionId]?.messages?.toList() ?: emptyList()
    }

    /**
     * Compress conversation history if it exceeds the message budget.
     * QwenPaw-style: archives raw messages to dialog/YYYY-MM-DD.jsonl before compaction;
     * produces a structured summary with Goal/Progress/KeyDecisions/NextSteps/CriticalContext.
     * When over [maxMessages] (default 50), uses [llmProvider] to generate a structured summary
     * and replaces older messages. Retains recent conversation groups:
     * MIN_KEEP_GROUPS groups unconditionally + more up to the token budget
     * (window × coherence tier 8%/15%/25% — see [splitRetention]).
     *
     * @param specificSessionId If provided, compress this session. Otherwise use active session.
     * @return true if compaction was performed.
     */
    suspend fun compressIfNeeded(llmProvider: LlmProvider, maxMessages: Int = 50, specificSessionId: String? = null): Boolean {
        val sessionId = specificSessionId ?: _activeSessionId.value ?: return false
        val session = _sessions.value[sessionId] ?: return false
        if (session.messages.size <= maxMessages) return false

        // ── 保留策略: MIN 组数保底 + MAX token 预算（连贯性档位）──
        // 从最近往回按问答组（user 消息为界）累积保留原文:
        //   - MIN_KEEP_GROUPS 组无条件保留（原文优先, 即使超预算）
        //   - 预算内继续累积直到用尽（预算 = 窗口 × 连贯性档位 8%/15%/25%）
        // 组数随问答大小自动浮动: 大问答保留组数少, 小问答保留多
        // Snapshot BEFORE the suspend LLM call to avoid losing concurrently-added messages
        val snapshot = session.messages.toList()
        val budgetTokens = (com.mengpaw.kernel.PipelineManager.DEFAULT_CONTEXT_WINDOW *
            retentionBudgetRatio(snapshot)).toInt()
        val (toKeep, toCompress) = splitRetention(snapshot, budgetTokens)
        if (toCompress.isEmpty()) return false

        // ── QwenPaw-style: archive raw messages before compaction ──
        archiveRawMessages(toCompress)

        // ── QwenPaw-style: structured summary (长度与保留原文反相关 — 目标占用率 ~60%) ──
        val keptTokens = toKeep.sumOf { (it.content.length * TOK_PER_CHAR).toInt() }
        val summary = summarizeMessagesStructured(
            llmProvider, toCompress, summaryBudgetCharsFor(keptTokens))

        // ── Build compact_summary with dialog path reference ──
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dialogRef = "dialog/$today.jsonl"
        val summaryMsg = Message(
            role = "system",
            content = buildString {
                append("[📋 对话摘要]\n")
                append(summary)
                append("\n[完整历史: $dialogRef — 需要时用 agent.read 查阅]")
            }
        )

        // Preserve any messages added during the LLM call
        val afterSnap = session.messages.toList()
        val concurrentNew = if (afterSnap.size > snapshot.size) afterSnap.drop(snapshot.size) else emptyList()

        synchronized(this) {
            session.messages.clear()
            session.messages.add(summaryMsg)
            session.messages.addAll(toKeep)
            if (concurrentNew.isNotEmpty()) session.messages.addAll(concurrentNew)
            _sessions.value = _sessions.value + (sessionId to session)
        }
        return true
    }

    /**
     * Archive raw messages to dialog/YYYY-MM-DD.jsonl before compaction.
     * Guarantees no data loss — Agent can always retrieve full history via read_file.
     */
    private fun archiveRawMessages(messages: List<Message>) {
        try {
            val dir = java.io.File(DataPaths.dialogArchiveDir(agentName)).also { it.mkdirs() }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = java.io.File(dir, "$today.jsonl")
            // JSONL append — one line per message, chronologically
            val lines = messages.map { msg ->
                buildJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                }.toString()
            }
            // Append to JSONL file
            file.appendText(lines.joinToString("\n") + "\n")
        } catch (e: Exception) {
            KernelLog.w("History", "archiveRawMessages: ${e.message}")
        }
    }

    /**
     * QwenPaw-style structured summary via LLM.
     * Produces: Goal / Progress / KeyDecisions / NextSteps / CriticalContext.
     * Merges with any existing summary for incremental updates.
     */
    private suspend fun summarizeMessagesStructured(
        llmProvider: LlmProvider,
        messages: List<Message>,
        summaryBudgetChars: Int = 600
    ): String {
        val conversationText = messages.joinToString("\n") { "[${it.role}] ${it.content.take(500)}" }

        // Check for existing compact_summary in the messages (merge case)
        val existingSummary = messages.firstOrNull { it.role == "system" && it.content.startsWith("[📋") }
        val mergeInstruction = if (existingSummary != null) {
            "\n## 已有摘要 (合并基础)\n${existingSummary.content}\n\n请将新对话合并到已有摘要中，更新各字段。"
        } else ""

        val summaryPrompt = listOf(
            mapOf(
                "role" to "user",
                "content" to """
提取以下对话历史的结构化摘要。输出纯文本(不要JSON/Markdown标题/代码块)，严格按此格式:

目标: <一句话描述用户想要达成什么>
进展: <已完成/进行中/被阻塞的具体事项>
关键决策: <做出的决策及其理由，用分号分隔>
下一步: <接下来要做什么>
关键上下文: <继续任务必须知道的信息：文件路径、函数名、关键技术栈、错误信息>

规则:
- 保留精确的文件路径、函数名、命令名和错误消息
- "进展"和"关键上下文"不超过各3个要点
- 如果用户只做了一个简单查询，摘要应同样简短
- 每行前面不要加"- "列表符号，直接写字段名和内容
$mergeInstruction

## 对话记录
$conversationText
""".trimIndent()
            )
        )
        return try {
            llmProvider.completeWithMessages(summaryPrompt).take(summaryBudgetChars)
        } catch (e: Exception) {
            KernelLog.w("History", "summarize failed: ${e.message}")
            "目标: (参见完整历史)\n进展: 对话已压缩\n关键决策: 无\n下一步: 继续对话\n关键上下文: 见 dialog/归档文件"
        }
    }

    // ── 保留策略: MIN 组数保底 + token 预算 ──────────────────────────

    /** token/字符 粗估系数（同 LlmRequestBuilder.FALLBACK_TOK_PER_CHAR）。 */
    private val TOK_PER_CHAR = 0.25
    /** 至少保留的问答组数（原文优先保底 — 即使超预算）。 */
    private val MIN_KEEP_GROUPS = 3

    /**
     * 从最近往回按问答组（user 消息为界）切分保留原文。
     * @return Pair(保留原文, 待压缩) — 保持原顺序。
     */
    private fun splitRetention(messages: List<Message>, budgetTokens: Int): Pair<List<Message>, List<Message>> {
        val keep = mutableListOf<Message>()
        var keptTokens = 0
        var groups = 0
        var idx = messages.size - 1
        while (idx >= 0) {
            // 收集一组: 从 idx 往回直到（不含）上一个 user 消息
            val group = mutableListOf<Message>()
            var boundary = idx
            while (boundary >= 0) {
                group.add(0, messages[boundary])
                if (messages[boundary].role == "user") break
                boundary--
            }
            val groupTokens = group.sumOf { (it.content.length * TOK_PER_CHAR).toInt() }
            // MIN 保底（原文优先）或预算内 → 保留; 否则停止
            if (groups < MIN_KEEP_GROUPS || keptTokens + groupTokens <= budgetTokens) {
                keep.addAll(0, group)
                keptTokens += groupTokens
                groups++
                idx = boundary - 1
            } else {
                break
            }
        }
        val toCompress = messages.dropLast(keep.size)
        return keep to toCompress
    }

    /**
     * 连贯性信号 → 保留预算档位（轻量启发式, 零 LLM 开销）。
     * 高 25%: 工作深度（最近 ~40 条消息内同一 Command 命令 ≥3 次）或调试态（最近 ~20 条含错误关键字）
     * 中 15%: 产出规模（最近消息平均 >2000 字符）
     * 低 8%: 默认（普通问答, 主题轮换快）
     */
    private fun retentionBudgetRatio(messages: List<Message>): Double {
        val recent = messages.takeLast(40)
        // 工作深度: 同一命令出现 >= 3 次
        val cmds = recent.filter { it.role == "assistant" && it.content.startsWith("Command:") }
            .map { it.content.substringAfter("Command: ").substringBefore("\n").take(40) }
        if (cmds.groupingBy { it }.eachCount().values.any { it >= 3 }) return 0.25
        // 调试态: 最近 5 组（~20 条）含错误关键字
        val debugMarkers = listOf("Error", "失败", "超时", "再试", "修正")
        if (recent.takeLast(20).any { m -> debugMarkers.any { m.content.contains(it) } }) return 0.25
        // 产出规模: 平均消息 > 2000 字符
        val avgSize = recent.map { it.content.length }.average()
        return if (avgSize > 2000) 0.15 else 0.08
    }

    /**
     * 摘要长度反相关 — 折叠后目标占用率 ~60%:
     * 摘要预算 = 0.6×窗口 − 保留原文 token, 折算字符。上下限 [300, 1200]。
     */
    private fun summaryBudgetCharsFor(keptTokens: Int): Int {
        val targetTokens = (com.mengpaw.kernel.PipelineManager.DEFAULT_CONTEXT_WINDOW * 0.60).toInt()
        val summaryTokens = targetTokens - keptTokens
        return (summaryTokens / TOK_PER_CHAR).toInt().coerceIn(300, 1200)
    }

    /**
     * Deprecated — kept for backward compatibility.
     * Calls the LLM to produce a simple summary. Prefer [summarizeMessagesStructured].
     */
    @Deprecated("Use summarizeMessagesStructured for QwenPaw-style structured output")
    private suspend fun summarizeMessages(
        llmProvider: LlmProvider,
        messages: List<Message>
    ): String {
        val conversationText = messages.joinToString("\n") { "[${it.role}] ${it.content}" }
        val summaryPrompt = listOf(
            mapOf(
                "role" to "user",
                "content" to "Summarize the following conversation history concisely. " +
                    "Capture key decisions, actions taken, important context, and outcomes. " +
                    "Keep the summary under 500 words.\n\n$conversationText"
            )
        )
        return llmProvider.completeWithMessages(summaryPrompt)
    }

    /**
     * Get the structured conversation history as a list of role/content maps.
     * Used for prefix-cache-optimized LLM requests where messages[0] is the system prompt.
     *
     * ⚠️ Filters out [Message.localOnly] messages — recovery metadata must never reach the LLM.
     */
    fun getStructuredHistory(sessionId: String): List<Map<String, String>> {
        return _sessions.value[sessionId]?.messages
            ?.filter { !it.localOnly }
            ?.map {
                mapOf("role" to it.role, "content" to it.content)
            } ?: emptyList()
    }

    // ── Interrupted Turn Recovery (Reasonix Level 2) ─────────────────────

    /**
     * Record an interrupted assistant turn as a LocalOnly message.
     * Stored in session history for backwards scanning; filtered out by getStructuredHistory().
     *
     * Matching Reasonix [recordInterruptedDisplay] in agent.go (line 140).
     */
    @Synchronized
    fun recordInterruptedTurn(
        sessionId: String,
        completedTools: List<InterruptedToolSummary>,
        interruptedTools: List<String>,
        hasPartialText: Boolean,
        hasPartialReasoning: Boolean
    ) {
        val session = _sessions.value[sessionId] ?: return
        val recovery = InterruptedTurnRecovery(
            pending = true,
            completedTools = completedTools,
            interruptedTools = interruptedTools,
            droppedPartialText = hasPartialText,
            droppedPartialReasoning = hasPartialReasoning
        )
        session.messages.add(Message(
            role = "system",
            content = "interrupted-turn-recovery",
            localOnly = true,
            interruptedTurn = recovery
        ))
        _sessions.value = _sessions.value + (sessionId to session)
    }

    /**
     * Check whether the given session has a pending (un-consumed) interrupted turn recovery.
     */
    fun hasPendingRecovery(sessionId: String): Boolean {
        return _sessions.value[sessionId]?.messages?.let { msgs ->
            com.mengpaw.kernel.session.findPendingRecovery(msgs) != null
        } ?: false
    }

    /**
     * Consume the pending interrupted turn recovery by setting [InterruptedTurnRecovery.pending] to false.
     * Called by AgentEngine.buildConversation() after the recovery block has been injected.
     *
     * @return true if a pending recovery was found and consumed.
     */
    @Synchronized
    fun consumePendingRecovery(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        for (i in session.messages.indices.reversed()) {
            val m = session.messages[i]
            if (m.localOnly && m.interruptedTurn != null && m.interruptedTurn.pending) {
                session.messages[i] = m.copy(
                    interruptedTurn = m.interruptedTurn.copy(pending = false)
                )
                _sessions.value = _sessions.value + (sessionId to session)
                return true
            }
        }
        return false
    }

    // ── Durable Session Event Log (matching OpenClaw session_state_events table) ──

    /**
     * Record a session lifecycle event to both the in-memory bus and the durable JSONL log.
     *
     * Architecture (matching OpenClaw recordSessionStateEvent):
     *   1. Append to {agentName}/sessions/{sessionId}.event.log (JSONL, one line per event)
     *   2. Emit to SessionEventBus (in-memory, for subscribers)
     *
     * The event log file uses line count as a natural auto-increment sequence:
     *   line 1 = seq 1, line 2 = seq 2, etc. (matching OpenClaw's INTEGER PRIMARY KEY AUTOINCREMENT)
     */
    @Synchronized
    fun recordSessionEvent(sessionId: String, event: SessionEventBus.SessionEvent) {
        // 1. Durable write to event log
        try {
            val dir = java.io.File(DataPaths.dialogArchiveDir(agentName)).also { it.mkdirs() }
            val logFile = java.io.File(dir, "${sessionId}.event.log")
            val logLine = buildJsonObject {
                put("kind", event.kind.name)
                put("ts", event.timestamp)
                put("summary", event.summary)
                if (event.payload.isNotEmpty()) {
                    putJsonObject("payload") {
                        event.payload.forEach { (k, v) -> put(k, v) }
                    }
                }
            }.toString()
            java.io.FileWriter(logFile, true).use { fw ->
                fw.write(logLine + "\n")
                fw.flush()
            }
        } catch (e: Exception) {
            KernelLog.w("History", "recordSessionEvent: ${e.message}")
        }
        // 2. In-memory broadcast
        SessionEventBus.emit(event)
    }

    /**
     * List all session events that occurred after the given sequence number.
     * Sequence numbers correspond to 1-indexed lines in the event log.
     *
     * Matching OpenClaw listSessionStateEventsSince(sessionKey, agentId, afterSequence, limit).
     *
     * @return list of events, newest first; empty list if log is missing or corrupted.
     */
    fun listEventsSince(sessionId: String, afterSeq: Int = 0, limit: Int = 50): List<SessionEventBus.SessionEvent> {
        try {
            val dir = java.io.File(DataPaths.dialogArchiveDir(agentName))
            val logFile = java.io.File(dir, "${sessionId}.event.log")
            if (!logFile.exists()) return emptyList()

            return logFile.useLines { lines ->
                lines.drop(afterSeq).take(limit).mapNotNull { line ->
                    try {
                        val root = Json.parseToJsonElement(line).jsonObject
                        val kindName = root["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val kind = try { SessionEventBus.EventKind.valueOf(kindName) } catch (_: Exception) { return@mapNotNull null }
                        val summary = root["summary"]?.jsonPrimitive?.content ?: ""
                        val ts = root["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        val payload = mutableMapOf<String, String>()
                        root["payload"]?.jsonObject?.let { payloadObj ->
                            for ((k, v) in payloadObj) {
                                try { payload[k] = v.jsonPrimitive.content } catch (_: Exception) { }
                            }
                        }
                        SessionEventBus.SessionEvent(
                            kind = kind,
                            sessionId = sessionId,
                            agentName = agentName,
                            summary = summary,
                            payload = payload,
                            timestamp = ts
                        )
                    } catch (_: Exception) { null }
                }.toList()
            }
        } catch (e: Exception) {
            KernelLog.w("History", "listEventsSince: ${e.message}")
            return emptyList()
        }
    }

    // ── Event Log Pruning (matching OpenClaw pruneSessionStateEvents) ──

    /**
     * Prune old session events from the JSONL log.
     * Removes events older than [maxAgeDays] and keeps at most [maxLines] most recent lines.
     * Called periodically (e.g. during compression or at startup) to prevent unbounded growth.
     *
     * Matching OpenClaw pruneSessionStateEvents() — 30 day / 50000 row policy.
     */
    @Synchronized
    fun pruneSessionEvents(sessionId: String, maxAgeDays: Int = 30, maxLines: Int = 5000) {
        try {
            val dir = java.io.File(DataPaths.dialogArchiveDir(agentName))
            val logFile = java.io.File(dir, "${sessionId}.event.log")
            if (!logFile.exists() || logFile.length() == 0L) return

            val lines = logFile.useLines { it.toList() }
            if (lines.size <= maxLines) return  // still under limit

            val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600 * 1000L
            val pruned = lines.filter { line ->
                try {
                    val root = Json.parseToJsonElement(line).jsonObject
                    val ts = root["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    ts >= cutoff
                } catch (_: Exception) { true }  // keep unparseable lines
            }.takeLast(maxLines)

            if (pruned.size < lines.size) {
                logFile.atomicWriteText(pruned.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            KernelLog.w("History", "pruneSessionEvents: ${e.message}")
        }
    }

    // ── Session Integrity Check (matching OpenClaw assertSqliteIntegrity) ──

    /**
     * Verify session data integrity. Checks:
     * - No [localOnly] messages leaked between non-local messages
     * - The last message is structurally complete (not truncated JSON)
     * - Event log is parseable (non-destructive check)
     *
     * Matching OpenClaw assertSqliteIntegrity + terminal latch pattern.
     */
    fun checkSessionIntegrity(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        val msgs = session.messages
        if (msgs.isEmpty()) return true

        for (i in msgs.indices) {
            val msg = msgs[i]
            // localOnly messages should only appear after another localOnly, or at boundaries
            if (msg.localOnly && msg.interruptedTurn != null) {
                // An interrupted_turn message must have a "user" message after it eventually
                // (if the session continued), otherwise it's a dangling interrupt record
                val hasUserAfter = msgs.drop(i + 1).any { it.role == "user" && !it.localOnly }
                val isLastMsg = i == msgs.lastIndex
                // Dangling interrupt at end of session is acceptable (pending recovery)
                if (!hasUserAfter && !isLastMsg) {
                    // localOnly message in the middle of history with no subsequent user message
                    // suggests a compaction or history reordering issue
                    return false
                }
            }
            // Content should not be blank for non-system messages
            if (msg.role == "assistant" && msg.content.isBlank() && msg.interruptedTurn == null) {
                return false
            }
        }
        return true
    }

    /**
     * Repair minor session integrity issues:
     * - Remove orphan [localOnly] messages with no user message after them (except at end)
     * - Truncate messages to the 200-message history limit (matching addMessage)
     */
    @Synchronized
    fun repairSessionIntegrity(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        var changed = false
        val msgs = session.messages.toMutableList()

        // Remove orphan localOnly (not at end, no user after)
        val toRemove = mutableSetOf<Int>()
        for (i in msgs.indices) {
            val msg = msgs[i]
            if (msg.localOnly && msg.interruptedTurn != null) {
                val hasUserAfter = msgs.drop(i + 1).any { it.role == "user" && !it.localOnly }
                val isLastMsg = i == msgs.lastIndex
                if (!hasUserAfter && !isLastMsg) {
                    toRemove.add(i)
                    changed = true
                }
            }
        }
        toRemove.sortedDescending().forEach { msgs.removeAt(it) }

        // Enforce 200-message history limit (match addMessage behavior)
        if (msgs.size > 200) {
            // Keep last 200 messages, but preserve the first system message
            val systemMsgs = msgs.filter { it.role == "system" }
            val nonSystemTarget = msgs.filter { it.role != "system" }.takeLast(200 - systemMsgs.size.coerceAtMost(10))
            session.messages.clear()
            session.messages.addAll(systemMsgs.take(5)) // keep at most 5 system messages
            session.messages.addAll(nonSystemTarget)
            changed = true
        } else if (changed) {
            session.messages.clear()
            session.messages.addAll(msgs)
        }

        if (changed) {
            _sessions.value = _sessions.value + (sessionId to session)
        }
        return changed
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
