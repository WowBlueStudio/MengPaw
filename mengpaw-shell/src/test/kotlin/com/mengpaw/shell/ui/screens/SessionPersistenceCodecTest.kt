// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentTrace
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复期消息 → 引擎历史映射测试 (P0-2 "Observation 丢失"修复)。
 *
 * 断言意图: 工具结果气泡 ([ChatMessageUi.CommandResult]) 必须进引擎历史 (system 观察),
 * 否则进程重启恢复后模型看不到中断前的工具结果, 会重复执行已完成的工具。
 * 同时锁定既有语义不被破坏: User/Agent/AgentWithTrace 的映射不变, 运行中的 trace 仍跳过。
 */
class SessionPersistenceCodecTest {

    @Test
    fun `CommandResult 映射为 system 观察`() {
        val msgs = listOf(
            ChatMessageUi.User("看看仓库状态"),
            ChatMessageUi.CommandResult("git status\nnothing to commit"),
            ChatMessageUi.CommandResult("deploy failed", isError = true)
        )

        val conversation = toEngineConversation(msgs)

        assertEquals(3, conversation.size)
        assertEquals("user" to "看看仓库状态", conversation[0])
        assertEquals("system" to "git status\nnothing to commit", conversation[1])
        assertEquals("system" to "deploy failed", conversation[2])
    }

    @Test
    fun `User 与 Agent 映射保持既有语义`() {
        val conversation = toEngineConversation(listOf(
            ChatMessageUi.User("任务"),
            ChatMessageUi.Agent("答复")
        ))

        assertEquals(listOf("user" to "任务", "assistant" to "答复"), conversation)
    }

    @Test
    fun `AgentWithTrace 运行中跳过 完成后取最终内容`() {
        val trace = ChatMessageUi.AgentWithTrace(
            finalContent = "完成",
            traces = listOf(AgentTrace(1, "想", "cmd", "obs")),
            isRunning = false
        )
        val running = trace.copy(finalContent = "进行中", isRunning = true)

        val conversation = toEngineConversation(listOf(running, trace))

        assertEquals(1, conversation.size)
        assertEquals("assistant" to "完成", conversation[0])
    }

    @Test
    fun `ThinkingProcess 不独立映射 避免上下文重复注入`() {
        val conversation = toEngineConversation(listOf(
            ChatMessageUi.ThinkingProcess(steps = emptyList()),
            ChatMessageUi.System("提示")
        ))

        assertTrue("过程容器与系统提示都不进引擎历史: $conversation", conversation.isEmpty())
    }
}
