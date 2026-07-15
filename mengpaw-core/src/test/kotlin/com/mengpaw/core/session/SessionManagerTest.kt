package com.mengpaw.core.session

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
}
