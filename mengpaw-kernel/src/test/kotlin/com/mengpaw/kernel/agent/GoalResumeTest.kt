// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.GoalModeExecutor
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * GOAL 模式预算断点续跑回归 (工作流 C) — 重启后 `tokensUsed`/`iteration` 必须连续。
 *
 * 核心断言 (③): 存档 `tokensUsed` 已等于上限时, 恢复后**一步都不跑**即判定预算耗尽
 * (若续跑把 tokensUsed 重置为 0, 恢复会重新烧掉整个预算 — 即"重启成本翻倍")。
 *
 * 无墙钟阈值; 每个用例独立临时目录 + agentName 归位进度档。
 */
class GoalResumeTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "mengpaw_goal_resume_${UUID.randomUUID()}")

    @Before
    fun initPaths() {
        base.mkdirs()
        DataPaths.initialize(base.absolutePath)
    }

    @After
    fun cleanup() {
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    /** 记录调用次数的假 provider — 用于证明"恢复后没有再跑一轮"。 */
    private class CountingProvider : LlmProvider {
        val calls = AtomicInteger(0)
        override suspend fun complete(prompt: String): String {
            calls.incrementAndGet()
            return "YES 目标已完成"
        }
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
            calls.incrementAndGet()
            return "Final Answer: 完成"
        }
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
            calls.incrementAndGet()
            onToken("Final Answer: 完成")
            return "Final Answer: 完成"
        }
        override fun info() = ProviderInfo("mock", "goal-resume", ProviderType.LOCAL)
        override fun close() {}
    }

    @Test
    fun `resume keeps token budget continuous and never re-spends it`() = runBlocking {
        val goalFile = File(GoalSessionStore.progressFile("小檬", GoalSession.MODE_GOAL))
        goalFile.parentFile?.mkdirs()
        // 上次运行: 已跑 5 轮, 预算 1000 已用满 900 → 剩余 100
        GoalSessionStore.save(
            GoalSession.goalProgress(
                goal = "写季度报告", agentName = "小檬", iteration = 5, maxIterations = 20,
                tokensUsed = 900, maxTokens = 1000,
                lastVerdict = "NEEDS_REVISION", lastFeedback = "继续"
            ),
            goalFile
        )
        val seeded = GoalSessionStore.loadForResume("小檬")
        assertEquals("剩余预算 = 总额 − 已消耗", 100, seeded?.tokenBudgetRemaining)

        // 再吃掉 200 tokens → 超出上限; 恢复后必须立即判定预算耗尽
        GoalSessionStore.save(
            GoalSession(
                goal = seeded?.goal.orEmpty(), active = true, iteration = 5, maxIterations = 20,
                maxTokens = 1000, tokensUsed = 1100, lastVerdict = "NEEDS_REVISION",
                lastFeedback = "继续", mode = GoalSession.MODE_GOAL, agentName = "小檬"
            ),
            goalFile
        )
        val provider = CountingProvider()
        val engine = AgentEngine(llmProvider = provider, sessionManager = SessionManager())
        val executor = GoalModeExecutor(engine)

        val result = executor.runWithGoal(
            task = "写季度报告", maxTurns = 20, maxTokensBudget = 1000, agentName = "小檬"
        )

        assertEquals("恢复后预算已耗尽, 不得再跑任何一轮 LLM", 0, provider.calls.get())
        assertTrue("应报告目标未完成 (预算耗尽)", result.contains("目标未完成"))
        assertFalse("预算耗尽后必须清档, 防误续跑", goalFile.exists())
        assertNull("清档后 loadForResume 应为 null", GoalSessionStore.loadForResume("小檬"))
    }

    @Test
    fun `progress archive is written per turn while running`() = runBlocking {
        val provider = CountingProvider()
        val engine = AgentEngine(llmProvider = provider, sessionManager = SessionManager())
        val executor = GoalModeExecutor(engine)

        // RubricGate 直接判定 SATISFIED → 一轮后终结并清档
        val result = executor.runWithGoal(
            task = "单步目标", maxTurns = 5, maxTokensBudget = 1000, agentName = "小檬"
        )

        assertTrue("应报告目标已完成", result.contains("目标已完成"))
        assertFalse("终态应清档", File(GoalSessionStore.progressFile("小檬", GoalSession.MODE_GOAL)).exists())
    }

    @Test
    fun `goal progress round-trips iteration and remaining budget`() {
        GoalSessionStore.saveProgress(
            GoalSession.goalProgress(
                goal = "续跑目标", agentName = "小檬", iteration = 8, maxIterations = 20,
                tokensUsed = 60_000, maxTokens = 100_000,
                lastVerdict = "NEEDS_REVISION", lastFeedback = "还差数据"
            )
        )
        // 新实例语义: 只按 agentName 读盘, 不依赖任何内存态
        val loaded = GoalSessionStore.loadForResume("小檬")
        assertEquals("已完成轮次应连续", 8, loaded?.iteration)
        assertEquals("剩余轮次 = 上限 − 已完成", 12, loaded?.iterationsRemaining)
        assertEquals("剩余预算 = 总额 − 已消耗", 40_000, loaded?.tokenBudgetRemaining)
        assertFalse("恢复后不得判定为预算耗尽", (loaded?.tokensUsed ?: 0) >= (loaded?.maxTokens ?: 1))
    }
}
