// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import org.junit.Assert.*
import org.junit.Test

class SessionManagerIntegrityTest {
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
    fun `repairSessionIntegrity removes blank assistant messages`() {
        // v0.28.7: 空响应产物(空白 assistant 消息)会让 checkSessionIntegrity 永久失败,
        // 完整性 latch 锁死后续轮次 — repair 必须能清掉
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "hello"))
        manager.addMessage(session.id, Message("assistant", "")) // 空响应
        manager.addMessage(session.id, Message("user", "world"))
        manager.addMessage(session.id, Message("assistant", "hi"))

        assertFalse("含空白消息时完整性检查应失败", manager.checkSessionIntegrity(session.id))
        val changed = manager.repairSessionIntegrity(session.id)
        assertTrue(changed)
        assertTrue("修复后完整性检查应通过", manager.checkSessionIntegrity(session.id))
        val history = manager.getHistory(session.id)
        assertFalse("不应残留空白 assistant 消息", history.any { it.role == "assistant" && it.content.isBlank() })
        assertEquals(3, history.size) // user → (blank removed) → user → assistant
    }

    @Test
    fun `repairSessionIntegrity does not fail on nonexistent session`() {
        val manager = SessionManager()
        val result = manager.repairSessionIntegrity("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `checkSessionIntegrity handles empty session gracefully`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val result = manager.checkSessionIntegrity(session.id)
        assertTrue(result) // empty session with just the create is clean
    }
}
