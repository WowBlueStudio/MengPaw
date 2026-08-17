// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThinkingProcessWriter 闭环回归测试 (v0.36.2 P1)。
 *
 * 背景 BUG: 引擎对无 "Final Answer:" 标记的纯文本/Thought-only 输出 (parse Rule 3/4)
 * 也判为最终答案, 但 TaskExecutionPipeline 流式检测只认 "Final Answer:" 前缀 →
 * beginFinalAnswer 永不被调用, 思考容器 isRunning 永 true、collapsed 永 false:
 * 自动折叠失效, 手动折叠后 LazyColumn 重组又恢复展开。
 *
 * 本测试锁住: ① 未闭环时容器保持运行态 (复现); ② beginFinalAnswer 折叠容器 + 创建
 * FinalAnswer 气泡 (闭环); ③ finalize 定型最终答案。
 */
class ThinkingProcessWriterTest {

    private class FakeLlmProvider : LlmProvider {
        override suspend fun complete(prompt: String) = "ok"
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
            onToken("ok")
            return "ok"
        }
        override fun info() = ProviderInfo("test", "test", ProviderType.LOCAL)
        override fun close() {}
    }

    private fun newSession(): AgentSession {
        val provider = FakeLlmProvider()
        return AgentSession(
            name = "TestAgent",
            framework = null,
            modelName = "test",
            provider = provider,
            engine = AgentEngine(llmProvider = provider),
            messages = MutableStateFlow(emptyList()),
            scrollContext = ScrollContextManager("TestAgent")
        )
    }

    private fun sessionMessages(session: AgentSession): List<ChatMessageUi> =
        session.messages.value

    @Test
    fun `start后未闭环时思考容器保持运行态`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        writer.pushThought("第一轮思考", roundId = 0)

        val tp = sessionMessages(session).first() as ChatMessageUi.ThinkingProcess
        assertEquals("思考容器初始应为展开", false, tp.collapsed)
        assertTrue("思考容器初始应处于运行态", tp.isRunning)
        assertEquals(1, tp.steps.size)
        assertEquals("第一轮思考", tp.steps[0].thought)
    }

    @Test
    fun `beginFinalAnswer折叠容器并创建最终答案气泡`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = "/test", agentRef = "agent")

        writer.start()
        writer.pushThought("思考", roundId = 0)
        writer.beginFinalAnswer()

        val messages = sessionMessages(session)
        assertEquals("闭环后应有两个消息: 思考容器 + 最终答案", 2, messages.size)

        val tp = messages[0] as ChatMessageUi.ThinkingProcess
        assertTrue("闭环后思考容器应折叠", tp.collapsed)
        assertFalse("闭环后思考容器应退出运行态", tp.isRunning)

        val fa = messages[1] as ChatMessageUi.FinalAnswer
        assertTrue("最终答案气泡应处于流式运行态", fa.isRunning)
        assertEquals("/test", fa.executionMode)
        assertEquals("agent", fa.agentRef)
    }

    @Test
    fun `finalize定型最终答案`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        writer.beginFinalAnswer()
        writer.pushFinal("部分答案")
        writer.finalize("完整答案")

        val fa = sessionMessages(session).last() as ChatMessageUi.FinalAnswer
        assertEquals("完整答案", fa.content)
        assertFalse("定型后应退出运行态", fa.isRunning)
    }

    @Test
    fun `闭环后不残留运行中的思考容器`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        writer.pushThought("思考", roundId = 0)
        writer.beginFinalAnswer()
        writer.finalize("答案")

        val runningProcesses = sessionMessages(session)
            .filterIsInstance<ChatMessageUi.ThinkingProcess>()
            .count { it.isRunning }
        assertEquals("闭环后不得残留运行中的思考容器", 0, runningProcesses)
        assertNotNull("最终答案气泡应存在", sessionMessages(session).lastOrNull { it is ChatMessageUi.FinalAnswer })
    }

    @Test
    fun `addTool与pushThought同轮不另起step`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        // 突发流场景: 完整 Action 行先于思考增量被检测 → 先插工具行
        writer.addTool("search", roundId = 1)
        writer.pushThought("需要查找天气", roundId = 1)
        writer.pushThought("需要查找北京天气", roundId = 1)

        val tp = sessionMessages(session).first() as ChatMessageUi.ThinkingProcess
        assertEquals("同轮思考/工具必须合并为一步", 1, tp.steps.size)
        assertEquals("需要查找北京天气", tp.steps[0].thought)
        assertEquals(1, tp.steps[0].tools.size)
        assertEquals("search", tp.steps[0].tools[0].command)
    }

    @Test
    fun `跨轮pushThought另起新step`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        writer.pushThought("第一轮思考", roundId = 1)
        writer.addTool("search", roundId = 1)
        writer.completeTool("search q=天气", "晴天", isError = false)
        writer.pushThought("第二轮思考", roundId = 2)
        writer.addTool("agent.write", roundId = 2)

        val tp = sessionMessages(session).first() as ChatMessageUi.ThinkingProcess
        assertEquals("两轮必须拆成两步", 2, tp.steps.size)
        assertEquals("第一轮思考", tp.steps[0].thought)
        assertEquals(1, tp.steps[0].tools.size)
        assertEquals("晴天", tp.steps[0].tools[0].observation)
        assertEquals("第二轮思考", tp.steps[1].thought)
        assertEquals(1, tp.steps[1].tools.size)
        assertEquals("agent.write", tp.steps[1].tools[0].command)
    }

    @Test
    fun `addTool先建多轮step后pushThought按roundId回填不另起step`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        // v0.37.3 回归: 引擎回调先为两轮都插入工具行 (steps=[r1+tools, r2+tools]),
        // 播放协程随后按轮次回填思考 — 旧实现 pushThought 只与 last 比较 roundId,
        // r1 思考到达时 last=r2 → 另起空思考 step, 产生 4 步重复 (用户可见乱象)。
        writer.addTool("a", roundId = 1)
        writer.addTool("b", roundId = 2)
        writer.pushThought("第一轮思考", roundId = 1)
        writer.pushThought("第二轮思考", roundId = 2)

        val tp = sessionMessages(session).first() as ChatMessageUi.ThinkingProcess
        assertEquals("两轮必须合并为两步 (不得产生空思考 step)", 2, tp.steps.size)
        assertEquals("第一轮思考", tp.steps[0].thought)
        assertEquals("a", tp.steps[0].tools[0].command)
        assertEquals("第二轮思考", tp.steps[1].thought)
        assertEquals("b", tp.steps[1].tools[0].command)
    }

    @Test
    fun `最终答案创建后思考回填_不得影响finalize定型`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        // v0.37.3 回归: 最终答案轮已创建 (tracker.ref=FinalAnswer) 后, 播放协程
        // 仍在回填前一思考轮 (updateProcess 会把 tracker.ref 改写为 ThinkingProcess) —
        // 修复前 pushFinal/finalize 依赖 ref 定位失败, FinalAnswer 残留 isRunning=true
        writer.beginFinalAnswer()
        writer.pushThought("残余思考", roundId = 5)
        writer.pushFinal("答案内容")
        writer.finalize("最终答案")

        val fa = sessionMessages(session).filterIsInstance<ChatMessageUi.FinalAnswer>().firstOrNull()
        assertTrue("FinalAnswer 必须存在", fa != null)
        assertEquals("最终答案", fa!!.content)
        assertFalse("定型后 isRunning 必须为 false (不再显示思考中计时)", fa.isRunning)
    }

    @Test
    fun `beginFinalAnswer幂等_只创建一个答案气泡`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        writer.beginFinalAnswer()
        writer.beginFinalAnswer()

        assertEquals(
            "onDelta 与引擎返回兜底双触发不得产生两个答案气泡",
            1,
            sessionMessages(session).count { it is ChatMessageUi.FinalAnswer }
        )
    }

    @Test
    fun `finalize兜底_无FinalAnswer时停止容器并追加Agent`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, modePrefix = null, agentRef = null)

        writer.start()
        writer.pushThought("思考", roundId = 0)
        // 异常路径: 从未 beginFinalAnswer 直接定型
        writer.finalize("答案")

        val tp = sessionMessages(session).filterIsInstance<ChatMessageUi.ThinkingProcess>().first()
        assertFalse("无答案气泡时容器必须退出运行态", tp.isRunning)
        assertTrue("无答案气泡时容器必须折叠", tp.collapsed)
        val agent = sessionMessages(session).filterIsInstance<ChatMessageUi.Agent>().lastOrNull()
        assertTrue("必须兜底追加 Agent 气泡", agent != null)
        assertEquals("答案", agent!!.content)
    }
}
