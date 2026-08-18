// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import org.junit.Assert.*
import org.junit.Test

class SessionManagerHistoryTest {
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
    fun `getStructuredHistory回传assistant的reasoning_content`() {
        // v0.41.1 未发布: DeepSeek 思考模式多轮工具调用必须回传 reasoning_content —
        // 历史拼接时 assistant 消息附 reasoning_content 键 (请求侧再按供应商过滤)
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "任务"))
        manager.addMessage(session.id, Message("assistant", "Action: search", reasoning = "先想一下"))
        val structured = manager.getStructuredHistory(session.id)
        assertEquals("assistant 消息应回传思维链", "先想一下", structured[1]["reasoning_content"])
    }

    @Test
    fun `getStructuredHistory无思维链时不带reasoning_content键`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        manager.addMessage(session.id, Message("user", "任务"))
        manager.addMessage(session.id, Message("assistant", "普通回复"))
        val structured = manager.getStructuredHistory(session.id)
        assertFalse("无思维链不得带空键", structured[1].containsKey("reasoning_content"))
    }

    @Test
    fun `getStructuredHistory attaches binary only to the last attachment user message`() {
        // v0.32.1+ 重发成本修复: 历史附件每轮全量 base64 会击穿上下文窗口 —
        // 仅最后一条带附件的 user 消息挂二进制键, 更早消息只保留路径文本
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val img = java.io.File.createTempFile("att", ".png")
        img.writeBytes(ByteArray(64) { 1 })
        try {
            val att = AttachmentData(type = "image", path = img.absolutePath, mimeType = "image/png")
            manager.addMessage(session.id, Message("user", "first with image", attachments = listOf(att)))
            manager.addMessage(session.id, Message("assistant", "understood"))
            manager.addMessage(session.id, Message("user", "second with image", attachments = listOf(att)))

            val structured = manager.getStructuredHistory(session.id)
            assertEquals(3, structured.size)
            assertFalse("早期附件消息不挂二进制键", structured[0].containsKey("_image"))
            assertFalse("assistant 消息不挂", structured[1].containsKey("_image"))
            assertTrue("最后一条附件消息挂 _image", structured[2].containsKey("_image"))
        } finally {
            img.delete()
        }
    }

    @Test
    fun `getStructuredHistory attaches binary when only one attachment message exists`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val img = java.io.File.createTempFile("single", ".png")
        img.writeBytes(ByteArray(64) { 2 })
        try {
            val att = AttachmentData(type = "image", path = img.absolutePath)
            manager.addMessage(session.id, Message("user", "solo", attachments = listOf(att)))
            manager.addMessage(session.id, Message("assistant", "ok"))
            val structured = manager.getStructuredHistory(session.id)
            assertTrue(structured[0].containsKey("_image"))
            assertEquals("user", structured[0]["role"])
            assertEquals("solo", structured[0]["content"])
        } finally {
            img.delete()
        }
    }
}
