// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionManagerTest {

    @Test
    fun `create session`() {
        val manager = SessionManager()
        val session = manager.createSession("Test task")
        assertNotNull(session.id)
        assertEquals("Test task", session.task)
    }

    @Test
    fun `add message to session`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")

        val added = manager.addMessage(session.id, Message("user", "hello"))
        assertTrue(added)

        val history = manager.getHistory(session.id)
        assertEquals(1, history.size)
        assertEquals("hello", history[0].content)
    }

    @Test
    fun `get nonexistent session returns null`() {
        val manager = SessionManager()
        assertNull(manager.getSession("nonexistent"))
    }

    @Test
    fun `delete session`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.deleteSession(session.id)
        assertNull(manager.getSession(session.id))
    }

    @Test
    fun `active session tracking`() {
        val manager = SessionManager()
        assertNull(manager.activeSessionId.value)

        val s1 = manager.createSession("Task 1")
        assertEquals(s1.id, manager.activeSessionId.value)

        val s2 = manager.createSession("Task 2")
        assertEquals(s2.id, manager.activeSessionId.value)

        manager.deleteSession(s2.id)
        assertEquals(s1.id, manager.activeSessionId.value)
    }

    @Test
    fun `compressIfNeeded does nothing when under threshold`() = runBlocking {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val mockLlm = MockLlmProvider { "should not be called" }

        // Add 10 messages (under threshold of 50)
        repeat(10) { i ->
            manager.addMessage(session.id, Message("user", "message $i"))
        }

        val didCompress = manager.compressIfNeeded(mockLlm)
        assertFalse(didCompress)
        val history = manager.getHistory(session.id)
        assertEquals(10, history.size)
        // All original messages should be intact
        assertEquals("message 0", history[0].content)
        assertEquals("message 9", history[9].content)
    }

    @Test
    fun `compressIfNeeded compresses when over threshold`() = runBlocking {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val mockLlm = MockLlmProvider { "summary of the conversation" }

        // Add 55 messages (over threshold of 50)
        repeat(55) { i ->
            manager.addMessage(session.id, Message("user", "message $i"))
        }

        val didCompress = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress)
        val history = manager.getHistory(session.id)
        // 1 system summary + 10 kept messages = 11
        assertEquals(11, history.size)
        assertEquals("system", history[0].role)
        assertTrue(history[0].content.contains("[📋 对话摘要]"))
        assertTrue(history[0].content.contains("summary of the conversation"))
        // Last 10 messages should be the most recent
        assertEquals("message 45", history[1].content)
        assertEquals("message 54", history[10].content)
    }

    @Test
    fun `compressIfNeeded can be called multiple times`() = runBlocking {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        var callCount = 0
        val mockLlm = MockLlmProvider {
            callCount++
            "summary iteration $callCount"
        }

        // Add 55 messages, compress
        repeat(55) { i ->
            manager.addMessage(session.id, Message("user", "message batch1 $i"))
        }
        val didCompress1 = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress1)
        assertEquals(1, callCount)
        var history = manager.getHistory(session.id)
        assertEquals(11, history.size)

        // Add another 45 messages, putting us at 56 again
        repeat(45) { i ->
            manager.addMessage(session.id, Message("user", "message batch2 $i"))
        }
        assertEquals(56, manager.getHistory(session.id).size)

        val didCompress2 = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress2)
        assertEquals(2, callCount)
        history = manager.getHistory(session.id)
        assertEquals(11, history.size)
        // The first message is the newest compression summary
        assertTrue(history[0].content.contains("[📋 对话摘要]"))
        assertTrue(history[0].content.contains("summary iteration 2"))
        // The last 10 messages are the most recent additions
        assertEquals("message batch2 44", history[10].content)
    }

    @Test
    fun `compressIfNeeded does nothing when no active session`() = runBlocking {
        val manager = SessionManager()
        val mockLlm = MockLlmProvider { "should not be called" }
        // No session created, should not throw
        val didCompress = manager.compressIfNeeded(mockLlm)
        assertFalse(didCompress)
    }

    @Test
    fun `recordInterruptedTurn sets pending recovery on active session`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.recordInterruptedTurn(
            sessionId = session.id,
            completedTools = listOf(InterruptedToolSummary("fs.write", listOf("test.txt"))),
            interruptedTools = emptyList(),
            hasPartialText = false,
            hasPartialReasoning = false
        )
        val history = manager.getHistory(session.id)
        assertEquals(1, history.size)
        assertTrue(history[0].localOnly)
        assertNotNull(history[0].interruptedTurn)
        assertTrue(history[0].interruptedTurn!!.pending)
        assertEquals("fs.write", history[0].interruptedTurn!!.completedTools[0].name)
    }

    @Test
    fun `consumePendingRecovery marks recovery as consumed`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.recordInterruptedTurn(
            sessionId = session.id,
            completedTools = listOf(InterruptedToolSummary("grep")),
            interruptedTools = emptyList(),
            hasPartialText = false,
            hasPartialReasoning = false
        )
        val consumed = manager.consumePendingRecovery(session.id)
        assertTrue(consumed)
        val history = manager.getHistory(session.id)
        assertFalse(history[0].interruptedTurn!!.pending)
    }

    @Test
    fun `consumePendingRecovery returns false when no pending recovery`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        assertFalse(manager.consumePendingRecovery(session.id))
    }

    @Test
    fun `checkSessionIntegrity returns true for clean session`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "hello"))
        manager.addMessage(session.id, Message("assistant", "hi"))
        val result = manager.checkSessionIntegrity(session.id)
        assertTrue(result) // true = clean
    }

    @Test
    fun `getStructuredHistory returns non-localOnly messages only`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "visible 1"))
        manager.recordInterruptedTurn(
            sessionId = session.id,
            completedTools = emptyList(),
            interruptedTools = listOf("grep"),
            hasPartialText = true,
            hasPartialReasoning = false
        )
        manager.addMessage(session.id, Message("assistant", "visible 2"))
        val structured = manager.getStructuredHistory(session.id)
        // Should exclude the localOnly recovery record
        assertEquals(2, structured.size)
        assertEquals("visible 1", structured[0]["content"])
        assertEquals("visible 2", structured[1]["content"])
    }

    @Test
    fun `deleteSession removes session`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "hello"))
        manager.deleteSession(session.id)
        assertNull(manager.getSession(session.id))
    }

    @Test
    fun `repairSessionIntegrity returns false for clean session`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "hello"))
        manager.addMessage(session.id, Message("assistant", "hi"))
        val changed = manager.repairSessionIntegrity(session.id)
        assertFalse(changed) // no repair needed
    }

    @Test
    fun `checkSessionIntegrity returns true for dangling interrupt at end`() {
        // An interrupted turn at the end of history is acceptable (pending recovery)
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "hello"))
        manager.recordInterruptedTurn(
            sessionId = session.id,
            completedTools = emptyList(),
            interruptedTools = listOf("grep"),
            hasPartialText = true,
            hasPartialReasoning = false
        )
        val result = manager.checkSessionIntegrity(session.id)
        assertTrue(result) // dangling at end is acceptable
    }

    @Test
    fun `checkSessionIntegrity returns false for blank assistant message`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "hello"))
        manager.addMessage(session.id, Message("assistant", "")) // blank content = corruption
        val result = manager.checkSessionIntegrity(session.id)
        assertFalse(result)
    }

    @Test
    fun `repairSessionIntegrity does not fail on nonexistent session`() {
        val manager = SessionManager()
        val result = manager.repairSessionIntegrity("nonexistent")
        assertFalse(result)
    }

    // ── EventLog (in-memory coverage) ───────────────────────────────

    @Test
    fun `recordSessionEvent emits on event bus`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val events = mutableListOf<SessionEventBus.SessionEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            SessionEventBus.events.collect { events.add(it) }
        }
        try {
            manager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.TOOL_EXECUTED,
                sessionId = session.id,
                agentName = "test",
                summary = "test event"
            ))
            // Give time for the shared flow to deliver
            Thread.sleep(100)
            assertTrue(events.any { it.kind == SessionEventBus.EventKind.TOOL_EXECUTED })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `checkSessionIntegrity handles empty session gracefully`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val result = manager.checkSessionIntegrity(session.id)
        assertTrue(result) // empty session with just the create is clean
    }

    @Test
    fun `multiple interruptions stack correctly`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "task 1"))
        manager.recordInterruptedTurn(
            sessionId = session.id,
            completedTools = listOf(InterruptedToolSummary("fs.write")),
            interruptedTools = emptyList(),
            hasPartialText = false,
            hasPartialReasoning = false
        )
        manager.consumePendingRecovery(session.id)

        manager.addMessage(session.id, Message("user", "task 2"))
        manager.recordInterruptedTurn(
            sessionId = session.id,
            completedTools = listOf(InterruptedToolSummary("grep")),
            interruptedTools = emptyList(),
            hasPartialText = false,
            hasPartialReasoning = false
        )

        val pending = findPendingRecovery(manager.getHistory(session.id))
        assertNotNull(pending)
        assertEquals("grep", pending!!.completedTools[0].name)
        assertTrue(pending.pending)
    }

    // Mock LlmProvider for testing
    private class MockLlmProvider(
        private val onComplete: () -> String
    ) : LlmProvider {
        override suspend fun complete(prompt: String): String = onComplete()
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String = onComplete()
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = onComplete()
        override fun info(): ProviderInfo = ProviderInfo("mock", "mock-model", ProviderType.LOCAL)
        override fun close() {}
    }
}
