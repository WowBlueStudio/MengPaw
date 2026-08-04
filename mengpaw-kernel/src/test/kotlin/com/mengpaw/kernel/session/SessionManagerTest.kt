// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

        // 55 条 ~800 字符消息（组 token ~200/条, 默认档 8% 预算 ≈10485 tokens →
        // 预算内保留 ~51 条 + MIN 3 组, 压缩前几条）
        repeat(55) { i ->
            manager.addMessage(session.id, Message("user", "message $i " + "x".repeat(800)))
        }

        val didCompress = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress)
        val history = manager.getHistory(session.id)
        // 摘要在前
        assertEquals("system", history[0].role)
        assertTrue(history[0].content.contains("[📋 对话摘要]"))
        assertTrue(history[0].content.contains("summary of the conversation"))
        // 保留最近原文（尾部 = 压缩前最后一条）; 条数 < 55 且 ≥ 摘要 + MIN 组
        assertEquals("message 54 " + "x".repeat(800), history.last().content)
        assertTrue("压缩后应少于 55 条: ${history.size}", history.size < 55)
        assertTrue("至少 MIN 组保底: ${history.size}", history.size >= 1 + 3)
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
            manager.addMessage(session.id, Message("user", "message batch1 $i " + "x".repeat(800)))
        }
        val didCompress1 = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress1)
        assertEquals(1, callCount)
        var history = manager.getHistory(session.id)
        assertTrue(history[0].content.contains("summary iteration 1"))

        // Add another 45 messages, putting us over threshold again
        repeat(45) { i ->
            manager.addMessage(session.id, Message("user", "message batch2 $i " + "x".repeat(800)))
        }

        val didCompress2 = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress2)
        assertEquals(2, callCount)
        history = manager.getHistory(session.id)
        // The first message is the newest compression summary
        assertTrue(history[0].content.contains("[📋 对话摘要]"))
        assertTrue(history[0].content.contains("summary iteration 2"))
        // The last message is the most recent addition
        assertEquals("message batch2 44 " + "x".repeat(800), history.last().content)
        assertTrue("压缩后应少于 100 条: ${history.size}", history.size < 100)
    }

    @Test
    fun `compressIfNeeded preserves messages added during LLM call`() = runBlocking {
        // v0.28.6 身份 diff 加固: 压缩 LLM 调用窗口内并发追加的消息不能丢
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val mockLlm = MockLlmProvider {
            // 模拟 LLM 调用窗口内主循环并发 addMessage (monitor 此刻空闲)
            manager.addMessage(session.id, Message("user", "concurrent-new"))
            manager.addMessage(session.id, Message("assistant", "concurrent-reply"))
            "summary of the conversation"
        }
        repeat(55) { i ->
            manager.addMessage(session.id, Message("user", "message $i " + "x".repeat(800)))
        }
        assertTrue(manager.compressIfNeeded(mockLlm))
        val history = manager.getHistory(session.id)
        assertTrue("压缩窗口内追加的 user 消息不能丢", history.any { it.content == "concurrent-new" })
        assertTrue("压缩窗口内追加的 assistant 消息不能丢", history.any { it.content == "concurrent-reply" })
        assertEquals("concurrent-reply", history.last().content)
    }

    @Test
    fun `scheduleCompressionIfNeeded single-flight and compresses in background`() = runBlocking {
        // v0.28.6 后台预压缩: 连续 schedule 只触发一次压缩; 完成后历史被摘要替换
        val manager = SessionManager()
        val session = manager.createSession("Test")
        var callCount = 0
        val mockLlm = MockLlmProvider { callCount++; "summary of the conversation" }
        repeat(60) { i ->
            manager.addMessage(session.id, Message("user", "message $i " + "x".repeat(800)))
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        try {
            manager.scheduleCompressionIfNeeded(session.id, scope, mockLlm, threshold = 50, margin = 8)
            manager.scheduleCompressionIfNeeded(session.id, scope, mockLlm, threshold = 50, margin = 8)
            delay(2000)
            assertEquals("单在途去重, 压缩只触发一次", 1, callCount)
            val history = manager.getHistory(session.id)
            assertTrue(history.first().role == "system" && history.first().content.contains("[📋 对话摘要]"))
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `oversized recent group falls back to MIN keep`() = runBlocking {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val mockLlm = MockLlmProvider { "summary" }

        // 57 条小消息 + 最近 3 组各 ~50K 字符（单组就超 8% 预算 ≈41940 字符）→ MIN 3 组兜底
        repeat(57) { i ->
            manager.addMessage(session.id, Message("user", "small $i"))
        }
        repeat(3) { i ->
            manager.addMessage(session.id, Message("user", "huge $i " + "y".repeat(50_000)))
        }

        val didCompress = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress)
        val history = manager.getHistory(session.id)
        // MIN 3 组原文无条件保留（即使超预算）: 摘要 + 3 组 = 4 条
        assertEquals(4, history.size)
        assertEquals("system", history[0].role)
        assertTrue("最近组应保留: ${history.last().content.take(20)}", history.last().content.startsWith("huge 2"))
    }

    @Test
    fun `production scale tier retains more than default`() = runBlocking {
        // 平均 >2000 字符（产出规模信号）→ 15% 档预算 ≈ 78K 字符 ≈ 35 条保留
        // （默认 8% 档同规模只保留 ~19 条 — 见 compresses when over threshold 用例）
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val mockLlm = MockLlmProvider { "summary" }
        repeat(200) { i ->
            manager.addMessage(session.id, Message("user", "m $i " + "x".repeat(2200)))
        }
        val didCompress = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress)
        val history = manager.getHistory(session.id)
        assertTrue("15% 档保留应多于 8% 档（~19 条）: ${history.size}", history.size > 25)
        assertTrue("最近的产出消息应保留", history.last().content.startsWith("m 199"))
    }

    @Test
    fun `high coherence budget retains more original messages`() = runBlocking {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val mockLlm = MockLlmProvider { "summary" }

        // 190 条普通消息 + 3 条同命令（最近 40 条内同一命令 ≥3 次 → 档位 25%）
        repeat(190) { i ->
            manager.addMessage(session.id, Message("user", "m $i " + "x".repeat(800)))
        }
        repeat(3) {
            manager.addMessage(session.id, Message("assistant", "Command: fs.cat /a\nResult: ..."))
        }

        val didCompress = manager.compressIfNeeded(mockLlm)
        assertTrue(didCompress)
        val history = manager.getHistory(session.id)
        // 25% 预算 ≈32768 tokens ≈131K 字符 → 保留远超默认档（8% 只 ~52 条）
        assertTrue("高连贯档应保留大量原文: ${history.size}", history.size > 100)
        assertTrue("最近的同命令消息应保留", history.last().content.startsWith("Command: fs.cat"))
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
