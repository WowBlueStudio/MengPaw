// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import org.junit.Assert.*
import org.junit.Test

class InterruptedRecoveryTest {

    // ── buildInterruptedRecoveryBlock ────────────────────────────────

    @Test
    fun `buildInterruptedRecoveryBlock with completed and interrupted tools`() {
        val recovery = InterruptedTurnRecovery(
            pending = true,
            completedTools = listOf(
                InterruptedToolSummary("fs.write", listOf("file.txt"), 10, 2),
                InterruptedToolSummary("read_file", listOf("readme.md"), 0, 0)
            ),
            interruptedTools = listOf("grep", "tavily.search")
        )
        val block = buildInterruptedRecoveryBlock(recovery)
        assertTrue(block.contains("<interrupted-turn-recovery>"))
        assertTrue(block.contains("completed_tools:"))
        assertTrue(block.contains("- fs.write files=file.txt diff=+10/-2"))
        assertTrue(block.contains("- read_file files=readme.md"))
        assertTrue(block.contains("interrupted_tools: grep, tavily.search"))
        assertTrue(block.contains("</interrupted-turn-recovery>"))
    }

    @Test
    fun `buildInterruptedRecoveryBlock with no contexts`() {
        val recovery = InterruptedTurnRecovery()
        val block = buildInterruptedRecoveryBlock(recovery)
        assertTrue(block.contains("<interrupted-turn-recovery>"))
        assertFalse(block.contains("completed_tools:"))
        assertFalse(block.contains("interrupted_tools:"))
    }

    // ── findPendingRecovery ──────────────────────────────────────────

    @Test
    fun `findPendingRecovery returns null when no messages`() {
        assertNull(findPendingRecovery(emptyList()))
    }

    @Test
    fun `findPendingRecovery returns null when no recovery message`() {
        val messages = listOf(
            Message("user", "hello"),
            Message("assistant", "hi there")
        )
        assertNull(findPendingRecovery(messages))
    }

    @Test
    fun `findPendingRecovery returns pending recovery when found`() {
        val recovery = InterruptedTurnRecovery(pending = true, completedTools = listOf(InterruptedToolSummary("fs.write")))
        val messages = listOf(
            Message("user", "hello"),
            Message("assistant", "running task", localOnly = true, interruptedTurn = recovery)
        )
        val result = findPendingRecovery(messages)
        assertNotNull(result)
        assertEquals("fs.write", result!!.completedTools[0].name)
        assertTrue(result.pending)
    }

    @Test
    fun `findPendingRecovery returns null when user moved on`() {
        val recovery = InterruptedTurnRecovery(pending = true)
        val messages = listOf(
            Message("assistant", "interrupted", localOnly = true, interruptedTurn = recovery),
            Message("user", "never mind") // user sent new message after interrupt
        )
        assertNull(findPendingRecovery(messages))
    }

    @Test
    fun `findPendingRecovery returns null for non-pending recovery`() {
        val recovery = InterruptedTurnRecovery(pending = false)
        val messages = listOf(
            Message("assistant", "done", localOnly = true, interruptedTurn = recovery)
        )
        assertNull(findPendingRecovery(messages))
    }

    @Test
    fun `findPendingRecovery returns first pending recovery scanning backwards`() {
        val recovery1 = InterruptedTurnRecovery(pending = false)
        val recovery2 = InterruptedTurnRecovery(pending = true, completedTools = listOf(InterruptedToolSummary("grep")))
        val messages = listOf(
            Message("assistant", "first", localOnly = true, interruptedTurn = recovery1),
            Message("assistant", "second", localOnly = true, interruptedTurn = recovery2)
        )
        val result = findPendingRecovery(messages)
        assertNotNull(result)
        assertEquals("grep", result!!.completedTools[0].name)
    }

    // ── extractToolSummary ──────────────────────────────────────────

    @Test
    fun `extractToolSummary parses simple command`() {
        val content = "Command: fs.write file.txt\nResult: +new line\n-old line\n"
        val result = extractToolSummary(content)
        assertNotNull(result)
        assertEquals("fs.write", result!!.name)
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
    }

    @Test
    fun `extractToolSummary returns null for non-command content`() {
        val result = extractToolSummary("Just some text without command prefix")
        assertNull(result)
    }

    @Test
    fun `extractToolSummary extracts file path from write command`() {
        val content = "Command: fs.write src/main.kt\nResult: done"
        val result = extractToolSummary(content)
        assertNotNull(result)
        assertEquals("fs.write", result!!.name)
        assertTrue(result.files.contains("src/main.kt"))
    }

    @Test
    fun `extractToolSummary handles empty command line`() {
        val content = "Command: \nResult: nothing"
        val result = extractToolSummary(content)
        assertNull(result)
    }

    // ── decideRecovery ──────────────────────────────────────────────

    @Test
    fun `decideRecovery returns NoAction for empty events and messages`() {
        val decision = decideRecovery(emptyList(), emptyList())
        assertTrue(decision is RecoveryDecision.NoAction)
    }

    @Test
    fun `decideRecovery returns NoAction when user message present without errors`() {
        val events = listOf(
            SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.USER_MESSAGE,
                sessionId = "s1", agentName = "test",
                summary = "user said something"
            )
        )
        val decision = decideRecovery(events, emptyList())
        assertTrue(decision is RecoveryDecision.NoAction)
    }

    @Test
    fun `decideRecovery returns SimpleRetry on timeout`() {
        val events = listOf(
            SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                sessionId = "s1", agentName = "test",
                summary = "Connection timed out"
            )
        )
        val decision = decideRecovery(events, emptyList())
        assertTrue(decision is RecoveryDecision.SimpleRetry)
    }

    @Test
    fun `decideRecovery returns SimpleRetry on Chinese timeout`() {
        val events = listOf(
            SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                sessionId = "s1", agentName = "test",
                summary = "请求超时"
            )
        )
        val decision = decideRecovery(events, emptyList())
        assertTrue(decision is RecoveryDecision.SimpleRetry)
    }

    @Test
    fun `decideRecovery returns SuggestCleanup on 5 consecutive failures`() {
        val events = (1..5).map { i ->
            SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                sessionId = "s1", agentName = "test",
                summary = "error $i",
                payload = mapOf("consecutive" to "true")
            )
        }
        val decision = decideRecovery(events, emptyList())
        assertTrue("Expected SuggestCleanup but got $decision", decision is RecoveryDecision.SuggestCleanup)
    }

    @Test
    fun `decideRecovery returns RecoverFromInterrupt on RUN_INTERRUPTED`() {
        val recovery = InterruptedTurnRecovery(pending = true, completedTools = listOf(InterruptedToolSummary("fs.write")))
        val messages = listOf(
            Message("assistant", "interrupted", localOnly = true, interruptedTurn = recovery)
        )
        val events = listOf(
            SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_INTERRUPTED,
                sessionId = "s1", agentName = "test",
                summary = "interrupted"
            )
        )
        val decision = decideRecovery(events, messages)
        assertTrue("Expected RecoverFromInterrupt but got $decision", decision is RecoveryDecision.RecoverFromInterrupt)
    }

    @Test
    fun `decideRecovery falls back to legacy scan when events empty`() {
        val recovery = InterruptedTurnRecovery(pending = true, completedTools = listOf(InterruptedToolSummary("fs.grep")))
        val messages = listOf(
            Message("assistant", "grep", localOnly = true, interruptedTurn = recovery)
        )
        val decision = decideRecovery(emptyList(), messages)
        assertTrue("Expected RecoverFromInterrupt but got $decision", decision is RecoveryDecision.RecoverFromInterrupt)
    }
}
