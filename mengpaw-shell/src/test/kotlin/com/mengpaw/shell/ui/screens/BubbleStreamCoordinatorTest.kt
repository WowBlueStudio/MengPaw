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
 * BubbleStreamCoordinator 行为测试 — 锁死"卡在第 1 轮"系列回归 (v0.37.3 拆分)。
 *
 * 历史缺陷 (反复出现 4 次): 编排状态散落 TaskExecutionPipeline, Final Answer 误判 /
 * 轮次封口缺失都会让思考容器停在当前步、后续 delta 不再进入思考气泡。
 * 本测试把每个历史坑固化为用例: 思考含字样不误判 / 真实最终答案触发 /
 * 截断轮次播完 / 兜底闭环。
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
    fun `思考轮含Final Answer字样但同轮有工具_不误判且后续思考正常分步`() = runBlocking {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)
        val job = coordinator.launchPlayback(this)

        // 第 1 轮: 思考行中段出现 "Final Answer:" 字样 (行首锚定不应误判)
        coordinator.onDelta("Thought: 我先查资料, 最终我给出 Final Answer: 待定\nAction: search\nAction Input: {}\n")
        coordinator.onStep("search", "观察结果1", false)
        assertFalse("思考轮行中字样不得误判为最终答案", coordinator.isFinalAnswerStarted)

        // 第 2 轮: 正常思考 + 工具
        coordinator.onDelta("Thought: 第二轮思考\nAction: search2\nAction Input: {}\n")
        coordinator.onStep("search2", "观察结果2", false)
        assertFalse("第二轮仍不得误判", coordinator.isFinalAnswerStarted)

        // 真实最终答案轮 (无工具)
        coordinator.onDelta("Final Answer: 真正的答案")
        assertTrue("独立成行的真实 Final Answer 必须触发", coordinator.isFinalAnswerStarted)
        coordinator.finish()
        job.join()

        val msgDump = session.messages.value.joinToString(" | ") { m ->
            when (m) {
                is ChatMessageUi.ThinkingProcess -> "TP(steps=${m.steps.size},running=${m.isRunning})"
                is ChatMessageUi.FinalAnswer -> "FA(len=${m.content.length})"
                else -> m::class.simpleName.orEmpty()
            }
        }
        assertTrue("播放必须产生消息, 实际=[$msgDump]", session.messages.value.size > 1)
        val proc = process(session)
        val dump = proc.steps.joinToString(" | ") { "r${it.roundId}:t=[${it.thought.take(12)}]tools=${it.tools.size}" }
        assertEquals("两轮思考必须分为两个 step, steps=[$dump]", 2, proc.steps.size)
        assertTrue("步骤含第二轮 roundId=1", proc.steps.any { it.roundId == 1L })
        // 顺序化显示: 每轮思考播完后工具行才落地, 且挂上观察
        proc.steps.forEach { step ->
            assertTrue("round${step.roundId} 思考必须完整", step.thought.isNotBlank())
            assertEquals("round${step.roundId} 工具行必须随轮次落地", 1, step.tools.size)
            assertTrue("round${step.roundId} 观察必须挂载", step.tools[0].observation.isNotBlank())
        }
    }

    @Test
    fun `真实最终答案到达_折叠容器并创建答案气泡_后续delta进答案`() = runBlocking {
        val session = newSession()
        val writer = ThinkingProcessWriter(session, null, null)
        writer.start()
        val coordinator = BubbleStreamCoordinator(writer)
        val job = coordinator.launchPlayback(this)

        coordinator.onDelta("Thought: 分析完成\nAction: verify\nAction Input: {}\n")
        coordinator.onStep("verify", "ok", false)
        coordinator.onDelta("Final Answer: 最终结论")
        coordinator.finish()
        job.join()

        assertTrue("最终答案必须触发", coordinator.isFinalAnswerStarted)
        val proc = process(session)
        assertFalse("容器必须折叠", proc.isRunning)
        val fa = session.messages.value.filterIsInstance<ChatMessageUi.FinalAnswer>().firstOrNull()
        assertTrue("必须存在 FinalAnswer 气泡", fa != null)
    }

    @Test
    fun `截断路径_未封口轮次也能播完`() = runBlocking {
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
        val round1 = proc.steps.firstOrNull { it.roundId == 1L }
        assertTrue("未封口的第二轮必须仍成 step", round1 != null)
        val dump = proc.steps.joinToString(" | ") { "r${it.roundId}:thought=[${it.thought}]tools=${it.tools.size}" }
        assertTrue(
            "第二轮思考必须完整播出, steps=[$dump]",
            round1!!.thought.contains("第二轮未封口")
        )
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
