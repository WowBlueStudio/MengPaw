// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.session

import com.mengpaw.core.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages Agent sessions and conversation history.
 */
class SessionManager {
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
     * When over [maxMessages] (default 50), uses [llmProvider] to summarize
     * older messages and replaces them with a single system summary message.
     * Keeps the last 10 messages intact for immediate context.
     */
    suspend fun compressIfNeeded(llmProvider: LlmProvider, maxMessages: Int = 50) {
        val sessionId = _activeSessionId.value ?: return
        val session = _sessions.value[sessionId] ?: return
        if (session.messages.size <= maxMessages) return

        val keepCount = 10
        // FIX A2: Snapshot BEFORE the suspend LLM call to avoid losing concurrently-added messages
        val snapshot = session.messages.toList()
        val toCompress = snapshot.dropLast(keepCount)
        if (toCompress.isEmpty()) return
        val toKeep = snapshot.takeLast(keepCount)

        val summary = summarizeMessages(llmProvider, toCompress)

        val summaryMsg = Message(
            role = "system",
            content = "[Compressed: previous ${toCompress.size} messages summarized as: $summary]"
        )
        // Preserve any messages added during the LLM call
        val afterSnap = session.messages.toList()
        val concurrentNew = if (afterSnap.size > snapshot.size) afterSnap.drop(snapshot.size) else emptyList()

        session.messages.clear()
        session.messages.add(summaryMsg)
        session.messages.addAll(toKeep)
        if (concurrentNew.isNotEmpty()) session.messages.addAll(concurrentNew)
        _sessions.value = _sessions.value + (sessionId to session)
    }

    /**
     * Calls the LLM to produce a concise summary of the given messages.
     */
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
