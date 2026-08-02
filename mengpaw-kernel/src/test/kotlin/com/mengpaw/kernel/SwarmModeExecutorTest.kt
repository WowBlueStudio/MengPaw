// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.AgentMemoryExecutor
import com.mengpaw.kernel.agent.SwarmBudget
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 火种模式 (Swarm Mode) 测试。
 *
 * 回归锚点: 会话隔离用例 (worker 不污染 conversationSessionId) — 钉死 Mission 时代的会话污染缺陷。
 * LLM mock 用共享 TestProviders（ScriptedLlmProvider / DelayLlmProvider）。
 */
class SwarmModeExecutorTest {

    private val DECOMPOSE_JSON =
        """[{"id":"a","desc":"子任务A","criteria":"完成A"},{"id":"b","desc":"子任务B","criteria":"完成B"}]"""

    private fun engineWith(provider: LlmProvider) =
        AgentEngine(llmProvider = provider, sessionManager = SessionManager())

    // ── 用例 1: 混合模型角色分发 ────────────────────────────────────

    @Test
    fun `mixed models dispatch by role`() = runBlocking {
        val planner = ScriptedLlmProvider(listOf(DECOMPOSE_JSON), "planner")
        val worker = ScriptedLlmProvider(listOf("Final Answer: 子任务A完成"), "worker")
        val verifier = ScriptedLlmProvider(listOf("VERDICT: PASS\nANALYSIS: 达标"), "verifier")
        val synthesizer = ScriptedLlmProvider(listOf("## 综合报告"), "synthesizer")
        val engine = engineWith(planner)

        val report = engine.runWithSwarm(
            task = "调研市场",
            roles = mapOf(
                "planner" to planner, "worker" to worker,
                "verifier" to verifier, "synthesizer" to synthesizer
            )
        )

        assertTrue("报告应含综合报告", report.contains("综合报告"))
        assertTrue("planner 被调 (拆解)", planner.calls.any { it.contains("decompos", ignoreCase = true) })
        assertTrue("worker 被调 (子任务)", worker.calls.isNotEmpty())
        assertTrue("verifier 被调 (VERDICT 格式)", verifier.calls.any { it.contains("VERDICT:") })
        assertTrue("synthesizer 被调", synthesizer.calls.any { it.contains("Synthesize", ignoreCase = true) })
    }

    // ── 用例 1b: Fleet 转发透传 roles（模型路由接线回归锚点）──────────

    @Test
    fun `runWithFleet forwards roles to swarm`() = runBlocking {
        val planner = ScriptedLlmProvider(listOf(DECOMPOSE_JSON), "fleet-planner")
        val worker = ScriptedLlmProvider(listOf("Final Answer: 子任务A完成"), "fleet-worker")
        val verifier = ScriptedLlmProvider(listOf("VERDICT: PASS\nANALYSIS: 达标"), "fleet-verifier")
        val synthesizer = ScriptedLlmProvider(listOf("## 综合报告"), "fleet-synthesizer")
        val engine = engineWith(planner)

        val report = engine.runWithFleet(
            task = "编队任务",
            roles = mapOf(
                "planner" to planner, "worker" to worker,
                "verifier" to verifier, "synthesizer" to synthesizer
            )
        )

        assertTrue("报告应含综合报告", report.contains("综合报告"))
        assertTrue("planner 收到拆解 (roles 透传生效)", planner.calls.any { it.contains("decompos", ignoreCase = true) })
        assertTrue("worker 收到子任务", worker.calls.isNotEmpty())
        assertTrue("verifier 被调", verifier.calls.any { it.contains("VERDICT:") })
        assertTrue("synthesizer 被调", synthesizer.calls.any { it.contains("Synthesize", ignoreCase = true) })
    }

    // ── 用例 2: 并行执行 (时序) ──────────────────────────────────────

    @Test
    fun `workers execute in parallel`() = runBlocking {
        // 4 子任务, 每 worker 2 轮 LLM (每轮 100ms) → 单 worker 200ms; 顺序执行需 ≥800ms
        val planner = ScriptedLlmProvider(listOf(
            """[{"id":"a","desc":"任务A","criteria":"完成A"},{"id":"b","desc":"任务B","criteria":"完成B"},""" +
                """{"id":"c","desc":"任务C","criteria":"完成C"},{"id":"d","desc":"任务D","criteria":"完成D"}]"""
        ), "planner")
        val verifier = ScriptedLlmProvider(listOf("VERDICT: PASS\nANALYSIS: 达标"), "verifier")
        val synthesizer = ScriptedLlmProvider(listOf("合成"), "synthesizer")
        val worker = DelayLlmProvider(
            100,
            listOf("Action: self.status\nAction Input:", "Final Answer: done")
        )
        val engine = engineWith(planner)

        val start = System.currentTimeMillis()
        engine.runWithSwarm(
            task = "并行任务",
            roles = mapOf(
                "planner" to planner, "worker" to worker,
                "verifier" to verifier, "synthesizer" to synthesizer
            ),
            maxSubtasks = 4, maxParallel = 4
        )
        val elapsed = System.currentTimeMillis() - start

        // 并行: ~200ms + 拆解/合成; 顺序: ≥800ms
        assertTrue("并行执行应显著快于顺序 (elapsed=$elapsed)", elapsed < 700)
    }

    // ── 用例 3: 会话隔离 (回归锚点) ─────────────────────────────────

    @Test
    fun `swarm workers do not pollute conversation session`() = runBlocking {
        val planner = ScriptedLlmProvider(listOf(DECOMPOSE_JSON), "planner")
        val worker = ScriptedLlmProvider(listOf("Final Answer: 隔离完成"), "worker")
        val verifier = ScriptedLlmProvider(listOf("VERDICT: PASS"), "verifier")
        val synthesizer = ScriptedLlmProvider(listOf("合成"), "synthesizer")
        val main = ScriptedLlmProvider(listOf("Final Answer: 新会话正常"), "main")
        val sessionManager = SessionManager()
        val engine = AgentEngine(llmProvider = main, sessionManager = sessionManager)

        engine.runWithSwarm(
            task = "隔离测试",
            roles = mapOf(
                "planner" to planner, "worker" to worker,
                "verifier" to verifier, "synthesizer" to synthesizer
            )
        )

        // worker 会话不入 conversationSessionId
        assertNull("swarm 不应触碰主会话", engine.currentConversationId())
        // worker 会话已销毁 (零待命)
        assertTrue("worker 会话应全部删除", sessionManager.sessions.value.isEmpty())

        // 后续主对话不受影响
        val result = engine.run("后续主任务")
        assertTrue("主会话应正常恢复", result.isNotBlank())
    }

    // ── 用例 4: 预算闸停线 ──────────────────────────────────────────

    @Test
    fun `shared step budget stops remaining subtasks`() = runBlocking {
        // 串行 (maxParallel=1) 保证确定性: 预算 2 步 → 前 2 任务完成, 后 2 任务 SKIPPED
        val planner = ScriptedLlmProvider(listOf(
            """[{"id":"a","desc":"任务A","criteria":"完成A"},{"id":"b","desc":"任务B","criteria":"完成B"},""" +
                """{"id":"c","desc":"任务C","criteria":"完成C"},{"id":"d","desc":"任务D","criteria":"完成D"}]"""
        ), "planner")
        val worker = ScriptedLlmProvider(listOf("Final Answer: 完成"), "worker")
        val verifier = ScriptedLlmProvider(listOf("VERDICT: PASS"), "verifier")
        val synthesizer = ScriptedLlmProvider(listOf("合成"), "synthesizer")
        val engine = engineWith(planner)

        val report = engine.runWithSwarm(
            task = "预算测试",
            roles = mapOf(
                "planner" to planner, "worker" to worker,
                "verifier" to verifier, "synthesizer" to synthesizer
            ),
            maxSubtasks = 4, maxParallel = 1, maxTotalSteps = 2
        )

        // 2 步预算: 2 个 worker 各 1 步, 后 2 个 SKIPPED
        assertTrue("报告应含 SKIPPED 标记", report.contains("⏭️"))
        assertTrue("报告应含 2 个跳过", report.contains("⏭️ 2"))
        // worker 仅被调 2 次 (2 步)
        assertEquals("worker 调用数 = 预算", 2, worker.calls.size)
    }

    // ── 用例 5: Andon 重派 (换模型) ────────────────────────────────

    @Test
    fun `andon redeploys to alternate worker model`() = runBlocking {
        val planner = ScriptedLlmProvider(listOf(DECOMPOSE_JSON), "planner")
        val failWorker = ScriptedLlmProvider(listOf("Final Answer: 无法完成"), "fail")
        val passWorker = ScriptedLlmProvider(listOf("Final Answer: 换模型重试完成"), "alt")
        // verifier: 先 FAIL 后 PASS
        val verifier = ScriptedLlmProvider(
            listOf(
                "VERDICT: FAIL\nANALYSIS: 结果不达标\nFIX: 尝试另一种方法",
                "VERDICT: PASS\nANALYSIS: 达标"
            ), "verifier"
        )
        val synthesizer = ScriptedLlmProvider(listOf("合成"), "synthesizer")
        val engine = engineWith(planner)

        val report = engine.runWithSwarm(
            task = "重派测试",
            roles = mapOf(
                "planner" to planner,
                "worker" to failWorker, "worker.alt" to passWorker,
                "verifier" to verifier, "synthesizer" to synthesizer
            ),
            maxSubtasks = 1
        )

        assertEquals("fail worker 恰被调 1 次", 1, failWorker.calls.size)
        assertTrue("重派到 worker.alt 模型", passWorker.calls.isNotEmpty())
        assertTrue("最终应验证通过 (统计行 ✅ 1)", report.contains("✅ 1"))
        assertTrue("无失败子任务 (统计行 ❌ 0)", report.contains("❌ 0"))
    }

    // ── 用例 6: Andon 终止 (不静默重试) ─────────────────────────────

    @Test
    fun `andon terminates after retry limit`() = runBlocking {
        val planner = ScriptedLlmProvider(listOf(DECOMPOSE_JSON), "planner")
        val worker = ScriptedLlmProvider(listOf("Final Answer: 结果"), "worker")
        val verifier = ScriptedLlmProvider(listOf("VERDICT: FAIL\nANALYSIS: 不达标\nFIX: 改进"), "verifier")
        val synthesizer = ScriptedLlmProvider(listOf("合成"), "synthesizer")
        val engine = engineWith(planner)

        val report = engine.runWithSwarm(
            task = "终止测试",
            roles = mapOf(
                "planner" to planner, "worker" to worker,
                "verifier" to verifier, "synthesizer" to synthesizer
            ),
            maxSubtasks = 1, maxRetriesPerSubtask = 1
        )

        // 1 初派 + 1 重派 = 2 次, 无无限循环
        assertEquals("worker 恰被调 2 次 (初派+重派)", 2, worker.calls.size)
        assertTrue("最终标记 1 个失败 (统计行 ❌ 1)", report.contains("❌ 1"))
    }

    // ── 用例 7: runWithFleet 向后兼容 ───────────────────────────────

    @Test
    fun `runWithFleet forwards to swarm`() = runBlocking {
        val main = ScriptedLlmProvider(
            listOf(DECOMPOSE_JSON, "Final Answer: 完成", "VERDICT: PASS", "合成报告"), "main"
        )
        val engine = engineWith(main)

        val report = engine.runWithFleet("舰队任务")

        assertTrue("报告以火种模式头开头", report.startsWith("## 火种模式:"))
        assertTrue("报告非空", report.isNotBlank())
    }

    // ── 用例 8: 角色缺省回退 ────────────────────────────────────────

    @Test
    fun `empty roles falls back to main provider`() = runBlocking {
        val main = ScriptedLlmProvider(
            listOf(DECOMPOSE_JSON, "Final Answer: 完成", "VERDICT: PASS", "合成报告"), "main"
        )
        val engine = engineWith(main)

        val report = engine.runWithSwarm("回退测试")

        assertTrue("报告正常", report.contains("合成报告"))
        assertTrue("planner 特征", main.calls.any { it.contains("decompos", ignoreCase = true) })
        assertTrue("verifier 特征", main.calls.any { it.contains("VERDICT:") })
        assertTrue("synthesizer 特征", main.calls.any { it.contains("Synthesize", ignoreCase = true) })
        assertTrue("worker 特征 (子任务文本)", main.calls.any { it.contains("子任务A") })
    }

    // ── 用例 9: SwarmBudget 纯单测 ──────────────────────────────────

    @Test
    fun `swarm budget consumes exactly max steps`() {
        val budget = SwarmBudget(3)
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse("第 4 次应拒绝", budget.tryConsume())
        assertTrue(budget.exhausted)
        assertEquals(3, budget.consumedSteps)
    }

    // ── 用例 10: 拆解兜底 ───────────────────────────────────────────

    @Test
    fun `decompose falls back to line parsing`() = runBlocking {
        val planner = ScriptedLlmProvider(listOf("- 任务A | 完成A\n* 任务B | 完成B"), "planner")
        val worker = ScriptedLlmProvider(listOf("Final Answer: 完成"), "worker")
        val verifier = ScriptedLlmProvider(listOf("VERDICT: PASS"), "verifier")
        val synthesizer = ScriptedLlmProvider(listOf("合成"), "synthesizer")
        val engine = engineWith(planner)

        val report = engine.runWithSwarm(
            task = "行解析测试",
            roles = mapOf(
                "planner" to planner, "worker" to worker,
                "verifier" to verifier, "synthesizer" to synthesizer
            ),
            maxSubtasks = 4
        )

        assertTrue("行解析产出 2 子任务", report.contains("子任务: 2"))
    }

    @Test
    fun `decompose total failure degrades to single agent`() = runBlocking {
        val planner = ScriptedLlmProvider(listOf("完全没有结构"), "planner")
        val engine = engineWith(planner)

        val result = engine.runWithSwarm("无法拆解的任务", roles = mapOf("planner" to planner))

        assertTrue("退化为主 Agent 执行", result.isNotBlank())
        // 兜底走 agentEngine.run(): planner 收到原始任务
        assertTrue("兜底任务进入主会话", planner.calls.any { it.contains("无法拆解的任务") })
    }

    // ── 用例 11: 记忆屏蔽 (swarm scope 不写记忆) ─────────────────────

    @Test
    fun `swarm scope blocks memory writes`() = runBlocking {
        val executor = AgentMemoryExecutor()
        val swarmCtx = ExecutionContext(sessionId = "s1", scope = "swarm")

        val record = executor.commands["memory.record"]!!(listOf("测试内容"), swarmCtx)
        assertTrue("record 被屏蔽", record.output.contains("不写记忆"))

        val keep = executor.commands["memory.keep"]!!(listOf("测试内容"), swarmCtx)
        assertTrue("keep 被屏蔽", keep.output.contains("不写记忆"))

        val write = executor.commands["memory.write"]!!(listOf("id", "内容"), swarmCtx)
        assertTrue("write 被屏蔽", write.output.contains("不写记忆"))

        val projectSave = executor.commands["memory.project.save"]!!(listOf("项目", "总结"), swarmCtx)
        assertTrue("project.save 被屏蔽", projectSave.output.contains("不写记忆"))
    }
}
