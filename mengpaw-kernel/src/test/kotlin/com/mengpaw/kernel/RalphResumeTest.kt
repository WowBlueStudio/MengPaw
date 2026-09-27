// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.GoalSession
import com.mengpaw.kernel.agent.GoalSessionStore
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ralph 断点续跑回归 (工作流 C) — 轮次序号与累计预算必须跨重启连续。
 *
 * 覆盖:
 * - ① 进度档往返: 新实例 (不依赖内存态) 读同一目录, round/预算/交接文本完整;
 * - ③ 预算恢复语义: 存档已消耗 = 总额时, 恢复后**不再新开一轮** (若重置为总额则会重跑);
 * - 轮次进度: 存档 round 决定续跑起点, objective 不一致视为全新迭代;
 * - 终态清档: COMPLETE/INCOMPLETE/BLOCKED 后不得再被续跑;
 * - 落盘走 [GoalSessionStore] (与 GOAL 同一套进度库 / 同一目录), 不产生第二状态源。
 *
 * 无墙钟阈值断言; 每个用例独立临时目录。
 */
class RalphResumeTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "mengpaw_ralph_resume_${UUID.randomUUID()}")

    @Before
    fun initPaths() {
        base.mkdirs()
        DataPaths.initialize(base.absolutePath)
    }

    @After
    fun cleanup() {
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    /** worker 侧响应由 [reply] 决定; [prompts] 记录每次 complete 的提示词 (worker 子任务 id 可辨)。 */
    private class CountingProvider(private val reply: () -> String) : LlmProvider {
        val prompts = CopyOnWriteArrayList<String>()
        val calls = AtomicInteger(0)
        override suspend fun complete(prompt: String): String {
            calls.incrementAndGet()
            prompts.add(prompt)
            return reply()
        }
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
            // 主链路无 onDelta 时走这条 (ReAct 循环默认路径) —— 必须与 complete(prompt) 一样
            // 计数并记录提示词, 否则 prompts 恒空, "续跑起点是否真的在第 2 轮"就无从断言。
            calls.incrementAndGet()
            prompts.add(messages.joinToString("\n") { it["content"].orEmpty() })
            return reply()
        }
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
            val r = reply()
            onToken(r)
            return r
        }
        override fun info() = ProviderInfo("mock", "ralph-resume", ProviderType.LOCAL)
        override fun close() {}
    }

    /** 永不给 Final Answer → worker 每轮耗尽步数预算, 且不会触发完成评估。 */
    private fun exhaustingProvider() = CountingProvider {
        "Thought: 继续\nAction: ping\nAction Input: {}"
    }

    private fun engineWith(provider: LlmProvider): AgentEngine {
        val engine = AgentEngine(llmProvider = provider, sessionManager = SessionManager())
        engine.setAgentIdentity("小檬", null, "mock")
        return engine
    }

    private fun sessionFile() = File(GoalSessionStore.progressFile("小檬", GoalSession.MODE_RALPH))

    private fun seedArchive(round: Int, consumed: Int, total: Int, tokens: Long, objective: String) {
        val file = sessionFile()
        file.parentFile?.mkdirs()
        GoalSessionStore.save(
            GoalSession(
                goal = objective, active = true, iteration = consumed, maxIterations = total,
                maxTokens = 0, tokensUsed = tokens.toInt(), lastVerdict = "", lastFeedback = "上一轮交接",
                mode = GoalSession.MODE_RALPH, agentName = "小檬",
                round = round, tokensConsumed = tokens, updatedAt = 1L
            ),
            file
        )
    }

    // ── ① 进度档往返 (新实例读同一目录) ───────────────────────────────

    @Test
    fun `ralph progress file round-trips rounds and budget`() {
        seedArchive(round = 2, consumed = 40, total = 60, tokens = 7_500L, objective = "写目标报告")

        val loaded = GoalSessionStore.load(sessionFile())

        assertNotNull("Ralph 进度档应可读回", loaded)
        assertEquals("已完成轮次应完整", 2, loaded?.round)
        assertEquals("看板已消耗步数应完整", 40, loaded?.iteration)
        assertEquals("看板总预算应完整", 60, loaded?.maxIterations)
        assertEquals("累计 tokens 应完整", 7_500L, loaded?.tokensConsumed)
        assertEquals("mode 应为 ralph", GoalSession.MODE_RALPH, loaded?.mode)
        assertEquals("交接文本应完整", "上一轮交接", loaded?.lastFeedback)
        assertTrue(
            "Ralph 档应落在 模式进度/ 下 (与 GOAL 同目录)",
            sessionFile().absolutePath.contains("模式进度")
        )
        assertFalse(
            "GOAL 档与 Ralph 档必须是不同文件 (不得互相覆盖)",
            GoalSessionStore.progressFile("小檬", GoalSession.MODE_GOAL) ==
                GoalSessionStore.progressFile("小檬", GoalSession.MODE_RALPH)
        )
    }

    // ── ③ 预算恢复语义: 已消耗 = 总额 → 恢复后不再新开一轮 ─────────────

    @Test
    fun `resume with exhausted budget does not restart rounds`() = runBlocking {
        seedArchive(round = 2, consumed = 40, total = 40, tokens = 500L, objective = "写目标报告")
        val provider = exhaustingProvider()
        val engine = engineWith(provider)

        val out = RalphRunner(engine).run(
            objective = "写目标报告", provider = provider, maxRounds = 3, maxStepsPerRound = 20,
            sessionFile = sessionFile()
        )

        assertEquals("预算恢复后不应新开轮次 (若重置为总额则会重跑)", 0, out.roundsUsed)
        assertEquals("累计 tokens 必须连续 (不是 0)", 500L, out.tokensUsed)
        assertTrue(
            "worker 最多只被调用 1 次 (证明用的是剩余 0, 不是总额 40)",
            provider.calls.get() <= 1
        )
        assertFalse("终态 (BLOCKED) 应清档, 防误续跑", sessionFile().exists())
    }

    // ── 轮次进度: 存档 round 决定续跑起点 ────────────────────────────

    @Test
    fun `resume continues from next round and only spends remaining budget`() = runBlocking {
        // 手工写回"第 1 轮完成后被杀"的存档: 已消耗 20 / 总额 40
        seedArchive(round = 1, consumed = 20, total = 40, tokens = 0L, objective = "写目标报告")
        val provider = exhaustingProvider()
        val engine = engineWith(provider)

        val out = RalphRunner(engine).run(
            objective = "写目标报告", provider = provider, maxRounds = 3, maxStepsPerRound = 20,
            sessionFile = sessionFile()
        )

        // 剩余 20 步只够 1 轮; 第 2 轮耗尽预算 → 第 3 轮 worker 无法开工 → BLOCKED
        assertEquals("续跑只应执行 2 轮 (剩余预算上限), 不得重跑已完成的第 1 轮", 2, out.roundsUsed)
        assertEquals("预算耗尽 → BLOCKED", RalphRunner.RalphStatus.BLOCKED, out.status)
        // 预算连续性证据 (取代原先"提示词里找 ralph-N"的断言 —— worker 提示词只带 target 描述,
        // 不含子任务 id, 那条断言恒假): 续跑只花剩余 20 步 → LLM 调用数约 20;
        // 若忽略旧档从第 1 轮重跑, 会花满 3×20 步 → 约 60 次调用。
        assertTrue(
            "续跑只能花剩余预算 (调用数应远小于重跑 3 轮的上限 60), 实际=${provider.calls.get()}",
            provider.calls.get() <= 30
        )
        assertFalse("终态应清档", sessionFile().exists())
    }

    @Test
    fun `different objective discards stale archive`() = runBlocking {
        seedArchive(round = 2, consumed = 40, total = 40, tokens = 900L, objective = "旧目标")
        val provider = exhaustingProvider()
        val engine = engineWith(provider)

        val out = RalphRunner(engine).run(
            objective = "全新的目标", provider = provider, maxRounds = 3, maxStepsPerRound = 20,
            sessionFile = sessionFile()
        )

        // 旧档 (round=2 / 旧目标) 不得被续跑: 若被续跑, 只会有 2 轮可跑且从第 3 轮起算。
        // 目标不同 → 视为全新迭代 → 从第 1 轮跑满本次上限 3 轮。
        assertEquals("不同目标应从第 1 轮重新开始 (跑满本次上限)", 3, out.roundsUsed)
        assertEquals("预算不得继承旧档", 0L, out.tokensUsed)
        // 终态 (BLOCKED: 永不给 Final Answer → 轮次耗尽) 按设计清档, 防误续跑已终结的迭代
        assertFalse("终态应清档 (不残留旧目标进度)", sessionFile().exists())
    }

    @Test
    fun `no session file keeps behavior unchanged and writes nothing`() = runBlocking {
        val provider = exhaustingProvider()
        val engine = engineWith(provider)

        val out = RalphRunner(engine).run(
            objective = "无档目标", provider = provider, maxRounds = 2, maxStepsPerRound = 20,
            sessionFile = null
        )

        assertEquals("无档时仍按 maxRounds 跑满", 2, out.roundsUsed)
        assertFalse("未指定存档文件时不得落盘", sessionFile().exists())
    }
}
