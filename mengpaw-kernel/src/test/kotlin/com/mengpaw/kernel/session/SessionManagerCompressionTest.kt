// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionManagerCompressionTest {
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
}
