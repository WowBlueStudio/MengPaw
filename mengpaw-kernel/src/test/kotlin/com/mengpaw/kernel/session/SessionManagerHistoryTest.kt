// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.buildGoalTrackingBlock
import org.junit.Assert.*
import org.junit.Test

class SessionManagerHistoryTest {
    // ── 对话需求跟踪块 (v0.41.1 未发布, 规则式) ──────────────────────

    @Test
    fun `目标块_单条消息只有当前重点`() {
        val block = buildGoalTrackingBlock(listOf("帮我查一下天气"))
        assertNotNull(block)
        assertTrue("最新需求应为当前重点", block!!.contains("当前重点: 帮我查一下天气"))
        assertFalse("无旧需求不应有待办条目 (规则文本含'待办'字样, 按行首格式判断)", block.contains("- 待办/背景:"))
    }

    @Test
    fun `目标块_多条消息最新为当前重点旧需求为待办`() {
        val block = buildGoalTrackingBlock(listOf("旧目标A", "旧目标B", "新需求C"))!!
        assertTrue("最新消息应是当前重点", block.contains("当前重点: 新需求C"))
        assertTrue("最近的旧需求应排在待办最前", block.indexOf("待办/背景: 旧目标B") < block.indexOf("待办/背景: 旧目标A"))
    }

    @Test
    fun `目标块_过滤系统引导与空消息`() {
        val block = buildGoalTrackingBlock(
            listOf("", "继续。输出 Action: <命令> 和 Action Input: <参数>。", "真实需求")
        )!!
        assertTrue("只保留真实用户需求", block.contains("真实需求"))
        assertFalse("系统引导不得进目标清单", block.contains("继续。输出 Action"))
    }

    @Test
    fun `目标块_限长只取最近5条`() {
        val reqs = (1..8).map { "需求$it" }
        val block = buildGoalTrackingBlock(reqs)!!
        assertTrue("最新需求在", block.contains("需求8"))
        assertFalse("超过 5 条的最旧需求被丢弃", block.contains("需求1"))
        assertFalse("第 4 条 (第 6 新) 也应被丢弃", block.contains("需求3"))
        assertTrue("第 5 新的需求应保留", block.contains("需求4"))
    }

    @Test
    fun `目标块_无有效需求返回null`() {
        assertNull(buildGoalTrackingBlock(emptyList()))
        assertNull(buildGoalTrackingBlock(listOf("", "继续。输出 Action: x")))
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
