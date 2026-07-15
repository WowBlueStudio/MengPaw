package com.mengpaw.core.session

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
