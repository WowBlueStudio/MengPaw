// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话持久化纯序列化逻辑测试 — 数据类 + 文件级 json 实例均不依赖 Context/Android,
 * 可脱离 SessionPersistenceService 类直接往返编解码。
 *
 * 覆盖: SessionRecord/SessionPersistenceData 往返一致、MessageData 缺失键默认值兼容
 * 旧文件、ignoreUnknownKeys 容错 (v0.33.0+ 新增键不破坏旧存档)。
 */
class SessionPersistenceDataTest {

    @Test
    fun SessionRecord往返一致() {
        val record = SessionPersistenceService.SessionRecord(
            id = "sess_1",
            title = "测试会话",
            preview = "第一段预览文本",
            timestamp = 1_752_800_000_000L,
            messageCount = 3,
            compacted = true,
            compactedSummary = "会话已压缩摘要",
            agentName = "MengPaw",
            framework = null,
            archived = true
        )
        val roundTrip = SessionPersistenceService.SessionRecord.fromJson(record.toJson())
        assertEquals(record, roundTrip)
    }

    @Test
    fun SessionRecord默认值字段往返() {
        val record = SessionPersistenceService.SessionRecord(
            id = "s2", title = "t", preview = "p", timestamp = 1L, messageCount = 0
        )
        // compacted/archived/framework 均走默认值
        val roundTrip = SessionPersistenceService.SessionRecord.fromJson(record.toJson())
        assertEquals(record, roundTrip)
        assertFalse(roundTrip.compacted)
        assertFalse(roundTrip.archived)
        assertNull(roundTrip.framework)
        assertEquals("", roundTrip.compactedSummary)
    }

    @Test
    fun SessionPersistenceData含消息轨迹附件往返() {
        val data = SessionPersistenceData(
            sessionId = "sess_x",
            engineSessionId = "engine_y",
            messages = listOf(
                MessageData(
                    type = "agent_step",
                    text = "步骤文本",
                    executionMode = "SWARM",
                    agentRef = "MengPaw",
                    isError = false,
                    attachments = listOf(
                        AttachmentData(
                            type = "image", path = "/tmp/a.png",
                            mimeType = "image/png", name = "a.png", size = 4096
                        )
                    ),
                    step = 2,
                    thought = "思考内容",
                    action = "fs.write",
                    isFinal = true
                ),
                MessageData(type = "text", text = "普通消息"),
                MessageData(
                    type = "command", text = "!ls", isError = true,
                    traces = listOf(
                        TraceData(step = 1, thought = "t1", action = "a1", observation = "o1"),
                        TraceData(step = 2, thought = "t2", action = "a2", observation = "")
                    )
                )
            )
        )
        val encoded = json.encodeToString(data)
        val decoded = json.decodeFromString<SessionPersistenceData>(encoded)
        assertEquals(data, decoded)
    }

    @Test
    fun MessageData缺失键取默认值_兼容旧文件() {
        // 旧版本文件无 executionMode/attachments/step 等键 — 必须零迁移可读
        val md = json.decodeFromString<MessageData>("""{"type":"text","text":"旧消息"}""")
        assertEquals("text", md.type)
        assertEquals("旧消息", md.text)
        assertNull(md.executionMode)
        assertNull(md.agentRef)
        assertFalse(md.isError)
        assertTrue(md.attachments.isEmpty())
        assertEquals(0, md.step)
        assertEquals("", md.thought)
        assertFalse(md.isFinal)
        assertTrue(md.traces.isEmpty())
    }

    @Test
    fun ignoreUnknownKeys容错未来版本字段() {
        // 向前兼容: 新版本写入的未知键不得导致解析失败
        val md = json.decodeFromString<MessageData>(
            """{"type":"user","text":"hi","futureField":{"a":1},"another":"x"}"""
        )
        assertEquals("hi", md.text)
    }

    @Test
    fun TraceData往返() {
        val trace = TraceData(step = 5, thought = "想", action = "做", observation = "看")
        val decoded = json.decodeFromString<TraceData>(json.encodeToString(trace))
        assertEquals(trace, decoded)
    }

    @Test
    fun `recoverInterruptedMessages归一化运行态_思考容器折叠_答案气泡定型`() {
        // v0.40.2 回归: 进程死亡落盘残留运行态消息, 重启后恒显"思考中… Ns"计时气泡。
        // AgentWithTrace / ThinkingProcess / FinalAnswer 三种运行态必须全部归一化。
        val msgs = listOf(
            ChatMessageUi.User("任务"),
            ChatMessageUi.AgentWithTrace(
                finalContent = "", traces = emptyList(), isRunning = true
            ),
            ChatMessageUi.ThinkingProcess(
                steps = listOf(ChatMessageUi.ProcessStep(thought = "思考")),
                isRunning = true, collapsed = false
            ),
            ChatMessageUi.FinalAnswer(content = "", isRunning = true)
        )

        val (recovered, wasStuck) = recoverInterruptedMessages(msgs)
        assertTrue("存在运行态消息必须标记恢复", wasStuck)
        assertEquals("4 条消息 + 1 条系统恢复提示", 5, recovered.size)
        assertTrue(recovered[1] is ChatMessageUi.Agent)

        val tp = recovered[2] as ChatMessageUi.ThinkingProcess
        assertFalse("思考容器必须退出运行态", tp.isRunning)
        assertTrue("思考容器必须折叠", tp.collapsed)
        assertEquals("思考内容必须保留可回看", "思考", tp.steps[0].thought)

        val fa = recovered[3]
        assertTrue("空白运行态 FinalAnswer 必须替换为提示", fa is ChatMessageUi.Agent)
        assertTrue("尾部必须追加系统恢复提示", recovered[4] is ChatMessageUi.System)
    }

    @Test
    fun `recoverInterruptedMessages_有内容的FinalAnswer保留并退出运行态`() {
        val msgs = listOf(
            ChatMessageUi.FinalAnswer(content = "部分答案", isRunning = true)
        )

        val (recovered, wasStuck) = recoverInterruptedMessages(msgs)
        assertTrue(wasStuck)
        val fa = recovered[0] as ChatMessageUi.FinalAnswer
        assertEquals("部分答案", fa.content)
        assertFalse("有内容答案气泡必须退出运行态", fa.isRunning)
    }
}
