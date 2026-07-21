// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.session

import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
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

        manager.compressIfNeeded(mockLlm)
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

        manager.compressIfNeeded(mockLlm)
        val history = manager.getHistory(session.id)
        // 1 system summary + 10 kept messages = 11
        assertEquals(11, history.size)
        assertEquals("system", history[0].role)
        assertTrue(history[0].content.contains("[Compressed: previous 45 messages summarized as: summary of the conversation]"))
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
        manager.compressIfNeeded(mockLlm)
        assertEquals(1, callCount)
        var history = manager.getHistory(session.id)
        assertEquals(11, history.size)

        // Add another 45 messages, putting us at 56 again
        repeat(45) { i ->
            manager.addMessage(session.id, Message("user", "message batch2 $i"))
        }
        assertEquals(56, manager.getHistory(session.id).size)

        manager.compressIfNeeded(mockLlm)
        assertEquals(2, callCount)
        history = manager.getHistory(session.id)
        assertEquals(11, history.size)
        // The first message is the newest compression summary
        assertTrue(history[0].content.contains("summary iteration 2"))
        // The last 10 messages are the most recent additions
        assertEquals("message batch2 44", history[10].content)
    }

    @Test
    fun `compressIfNeeded does nothing when no active session`() = runBlocking {
        val manager = SessionManager()
        val mockLlm = MockLlmProvider { "should not be called" }
        // No session created, should not throw
        manager.compressIfNeeded(mockLlm)
    }

    // Mock LlmProvider for testing
    private class MockLlmProvider(
        private val onComplete: () -> String
    ) : LlmProvider {
        override suspend fun complete(prompt: String): String = onComplete()
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String = onComplete()
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = onComplete()
        override fun info(): ProviderInfo = ProviderInfo("mock", "mock-model", ProviderType.LOCAL)
    }
}
