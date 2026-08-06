// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import org.junit.Assert.*
import org.junit.Test

class SessionManagerRecoveryTest {
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
}
