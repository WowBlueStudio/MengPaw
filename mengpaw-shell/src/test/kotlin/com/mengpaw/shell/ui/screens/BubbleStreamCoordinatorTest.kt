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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BubbleStreamCoordinator 行为测试 — 锁死"卡在第 1 轮"系列回归 (v0.37.3 拆分)
 * + v0.40.1 简化显示流程 (思考一次性显示, 最终答案流式)。
 *
 * 历史缺陷 (反复出现 4 次): 编排状态散落 TaskExecutionPipeline, Final Answer 误判 /
 * 轮次封口缺失都会让思考容器停在当前步、后续 delta 不再进入思考气泡。
 * v0.40.1 用户定案: 思考阶段取消逐字流式 — 工具轮在完整 Action 行检测到时
 * 一次性显示整轮思考 (思考先出现), 工具行在 onStep 挂观察; 最终答案轮仍
 * 流式输出并折叠容器。本测试把每个历史坑 + 新行为固化为用例。
 */
class BubbleStreamCoordinatorTest {

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

    private fun process(session: AgentSession): ChatMessageUi.ThinkingProcess =
        session.messages.value.filterIsInstance<ChatMessageUi.ThinkingProcess>().first()

    @Test
    fun `工具轮_思考一次性显示且工具行onStep后挂观察`() = runBlocking {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)
        val job = coordinator.launchPlayback(this)

        // 分片流式到达: 思考未完整前不显示 (取消逐字打字机)
        coordinator.onDelta("Thought: 我需要查")
        assertTrue("思考未完整 (无 Action 行) 前不得显示", process(session).steps.isEmpty())
        // Action 行完整落地 → 一次性显示整轮思考; 工具行等 onStep (工具完成)
        coordinator.onDelta("询北京天气\nAction: search\nAction Input: {\"q\":\"北京\"}\n")
        val beforeStep = process(session)
        assertEquals("思考必须一次性完整显示", "我需要查询北京天气", beforeStep.steps[0].thought.trim())
        assertTrue("工具行在 onStep 前不得出现", beforeStep.steps[0].tools.isEmpty())

        coordinator.onStep("search", "观察结果1", false)
        val afterStep = process(session)
        assertEquals("工具行必须在思考之后出现", 1, afterStep.steps[0].tools.size)
        assertEquals("search", afterStep.steps[0].tools[0].command)
        assertEquals("观察结果1", afterStep.steps[0].tools[0].observation)
        assertFalse("思考轮不得误判为最终答案", coordinator.isFinalAnswerStarted)

        coordinator.finish()
        job.join()
    }

    @Test
    fun `思考轮含Final Answer字样但同轮有工具_不误判且思考不截断`() = runBlocking {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)
        val job = coordinator.launchPlayback(this)

        // 第 1 轮: 思考行中段出现 "Final Answer:" 字样 (行首锚定不应误判,
        // v0.40.1 显示文本同样行首锚定 — 思考完整保留, 不截到字样后)
        coordinator.onDelta("Thought: 我先查资料, 最终我给出 Final Answer: 待定\nAction: search\nAction Input: {}\n")
        coordinator.onStep("search", "观察结果1", false)
        assertFalse("思考轮行中字样不得误判为最终答案", coordinator.isFinalAnswerStarted)
        assertEquals(
            "思考文本不得被行中 Final Answer 字样截断",
            "我先查资料, 最终我给出 Final Answer: 待定",
            process(session).steps[0].thought.trim()
        )

        // 第 2 轮: 正常思考 + 工具
        coordinator.onDelta("Thought: 第二轮思考\nAction: search2\nAction Input: {}\n")
        coordinator.onStep("search2", "观察结果2", false)
        assertFalse("第二轮仍不得误判", coordinator.isFinalAnswerStarted)

        // 真实最终答案轮 (无工具)
        coordinator.onDelta("Final Answer: 真正的答案")
        assertTrue("独立成行的真实 Final Answer 必须触发", coordinator.isFinalAnswerStarted)
        coordinator.finish()
        job.join()

        val proc = process(session)
        assertEquals("两轮思考必须分为两个 step", 2, proc.steps.size)
        proc.steps.forEach { step ->
            assertTrue("round${step.roundId} 思考必须完整", step.thought.isNotBlank())
            assertEquals("round${step.roundId} 工具行必须随轮次落地", 1, step.tools.size)
            assertTrue("round${step.roundId} 观察必须挂载", step.tools[0].observation.isNotBlank())
        }
    }

    @Test
    fun `真实最终答案到达_折叠容器并创建答案气泡_答案流式输出`() = runBlocking {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)
        val job = coordinator.launchPlayback(this)

        coordinator.onDelta("Thought: 分析完成\nAction: verify\nAction Input: {}\n")
        coordinator.onStep("verify", "ok", false)
        coordinator.onDelta("Final Answer: 最终结论")
        assertTrue("最终答案必须触发", coordinator.isFinalAnswerStarted)
        coordinator.finish()
        job.join()
        // 真实链路尾段: applyFinalResult 定型 (播放器已播完, 覆盖为完整答案)
        applyFinalResult(
            session, writer, coordinator.streamBuffer,
            displayResult = "最终结论", result = "最终结论",
            modePrefix = null, agentRef = null, pluginViewModel = null
        )

        val proc = process(session)
        assertFalse("容器必须折叠", proc.isRunning)
        assertTrue("容器折叠态必须置位", proc.collapsed)
        val fa = session.messages.value.filterIsInstance<ChatMessageUi.FinalAnswer>().firstOrNull()
        assertTrue("必须存在 FinalAnswer 气泡", fa != null)
        assertEquals("最终答案必须流式输出完整内容", "最终结论", fa!!.content.trim())
        assertFalse("定型后答案气泡退出运行态", fa.isRunning)
    }

    @Test
    fun `纯思考轮_onStep时一次性显示`() = runBlocking {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)
        val job = coordinator.launchPlayback(this)

        coordinator.onDelta("Thought: 纯思考内容\n")
        assertTrue("无 Action 行的纯思考轮不得提前显示", process(session).steps.isEmpty())
        coordinator.onStep(null, null, false)
        assertEquals("纯思考轮 onStep 后一次性显示", 1, process(session).steps.size)
        assertEquals("纯思考内容", process(session).steps[0].thought.trim())
        coordinator.finish()
        job.join()
    }

    @Test
    fun `截断路径_未封口轮次也能一次性显示思考与工具行`() = runBlocking {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)
        val job = coordinator.launchPlayback(this)

        coordinator.onDelta("Thought: 第一轮\nAction: a\nAction Input: {}\n")
        coordinator.onStep("a", "ok", false)
        // 模拟引擎截断: 第二轮 delta 到达后未走 onStep 就 run() 返回 (无 Final Answer 标记)
        coordinator.onDelta("Thought: 第二轮未封口\nAction: b\nAction Input: {}\n")
        coordinator.finish()
        job.join()

        val proc = process(session)
        assertEquals("两轮思考必须完整显示", 2, proc.steps.size)
        val round2 = proc.steps.firstOrNull { it.roundId == 1L }
        assertTrue("未封口的第二轮必须仍成 step", round2 != null)
        assertEquals("第二轮思考必须完整一次性显示", "第二轮未封口", round2!!.thought.trim())
        assertEquals("第二轮工具行必须兜底挂载", 1, round2.tools.size)
        assertEquals("b", round2.tools[0].command)
    }

    @Test
    fun `引擎返回兜底_无Final Answer标记也强制闭环`() {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)

        // 无任何 delta — 引擎直接返回 (parse Rule 3/4 纯文本答案)
        coordinator.ensureFinalAnswer()

        assertTrue(coordinator.isFinalAnswerStarted)
        val proc = process(session)
        assertFalse("容器必须折叠", proc.isRunning)
        assertTrue(
            "必须创建 FinalAnswer 气泡",
            session.messages.value.any { it is ChatMessageUi.FinalAnswer }
        )
    }
}
