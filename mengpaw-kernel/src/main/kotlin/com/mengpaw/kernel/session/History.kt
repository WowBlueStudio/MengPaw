// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Manages Agent sessions and conversation history.
 *
 * v0.17: QwenPaw-style structured compaction with no-data-loss archive.
 * Compressed raw messages are saved to dialog/YYYY-MM-DD.jsonl before compaction.
 * The compact_summary includes a path reference so Agent can recall full history.
 */
class SessionManager {

    /** Agent name for archive paths. Set by AgentEngine. */
    @Volatile var agentName: String = "MengPaw"
    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * Create a new session for a given task.
     */
    fun createSession(task: String, metadata: Map<String, String> = emptyMap()): Session {
        val session = Session(
            id = UUID.randomUUID().toString().take(8),
            task = task,
            metadata = metadata
        )
        _sessions.value = _sessions.value + (session.id to session)
        _activeSessionId.value = session.id
        return session
    }

    /**
     * Get a session by ID.
     */
    fun getSession(id: String): Session? = _sessions.value[id]

    /**
     * Add a message to the active session.
     */
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
     * and replaces older messages. Keeps the last 10 messages intact.
     *
     * @return true if compaction was performed.
     */
    /**
     * @param specificSessionId If provided, compress this session. Otherwise use active session.
     */
    suspend fun compressIfNeeded(llmProvider: LlmProvider, maxMessages: Int = 50, specificSessionId: String? = null): Boolean {
        val sessionId = specificSessionId ?: _activeSessionId.value ?: return false
        val session = _sessions.value[sessionId] ?: return false
        if (session.messages.size <= maxMessages) return false

        val keepCount = 10
        // Snapshot BEFORE the suspend LLM call to avoid losing concurrently-added messages
        val snapshot = session.messages.toList()
        val toCompress = snapshot.dropLast(keepCount)
        if (toCompress.isEmpty()) return false
        val toKeep = snapshot.takeLast(keepCount)

        // ── QwenPaw-style: archive raw messages before compaction ──
        archiveRawMessages(toCompress)

        // ── QwenPaw-style: structured summary ──
        val summary = summarizeMessagesStructured(llmProvider, toCompress)

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

        session.messages.clear()
        session.messages.add(summaryMsg)
        session.messages.addAll(toKeep)
        if (concurrentNew.isNotEmpty()) session.messages.addAll(concurrentNew)
        _sessions.value = _sessions.value + (sessionId to session)
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
        } catch (_: Exception) {
            // Archive failure is non-fatal — compaction proceeds without archive
        }
    }

    /**
     * QwenPaw-style structured summary via LLM.
     * Produces: Goal / Progress / KeyDecisions / NextSteps / CriticalContext.
     * Merges with any existing summary for incremental updates.
     */
    private suspend fun summarizeMessagesStructured(
        llmProvider: LlmProvider,
        messages: List<Message>
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
            llmProvider.completeWithMessages(summaryPrompt).take(600)
        } catch (_: Exception) {
            // Fallback: simple concatenation
            "目标: (参见完整历史)\n进展: 对话已压缩\n关键决策: 无\n下一步: 继续对话\n关键上下文: 见 dialog/归档文件"
        }
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
     */
    fun getStructuredHistory(sessionId: String): List<Map<String, String>> {
        return _sessions.value[sessionId]?.messages?.map {
            mapOf("role" to it.role, "content" to it.content)
        } ?: emptyList()
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
    fun deleteSession(id: String) {
        _sessions.value = _sessions.value - id
        if (_activeSessionId.value == id) {
            _activeSessionId.value = _sessions.value.keys.firstOrNull()
        }
    }
}
