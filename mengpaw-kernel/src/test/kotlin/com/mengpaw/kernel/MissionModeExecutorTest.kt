// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.AgentMemoryExecutor
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mission 并行化测试 — 移植 SwarmModeExecutorTest 模式:
 * 并行时序 / 会话隔离(零待命) / 记忆屏蔽 / 重试精确 / 拆解兜底 / 报告结构。
 */
class MissionModeExecutorTest {

    // ── 测试基建 ──────────────────────────────────────────────────────

    private class ScriptedLlmProvider(
        private val responses: List<String>,
        val tag: String = "mock"
    ) : LlmProvider {
        val calls = CopyOnWriteArrayList<String>()
        private val idx = AtomicInteger(0)

        override suspend fun complete(prompt: String): String {
            calls.add(prompt)
            return responses[idx.getAndIncrement().coerceAtMost(responses.lastIndex)]
        }

        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
            complete(messages.joinToString("\n") { "${it["role"]}:${it["content"]}" })

        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            complete(prompt).also { onToken(it) }

        override fun info(): ProviderInfo = ProviderInfo("mock", tag, ProviderType.LOCAL)
        override fun close() {}
    }

    /** 每次调用 delay — 并行时序断言用。 */
    private class DelayLlmProvider(
        private val delayMs: Long = 100,
        private val responses: List<String>
    ) : LlmProvider {
        val calls = CopyOnWriteArrayList<String>()
        private val idx = AtomicInteger(0)

        override suspend fun complete(prompt: String): String {
            calls.add(prompt)
            delay(delayMs)
            return responses[idx.getAndIncrement().coerceAtMost(responses.lastIndex)]
        }

        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
            complete(messages.joinToString("\n") { "${it["role"]}:${it["content"]}" })

        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            complete(prompt).also { onToken(it) }

        override fun info(): ProviderInfo = ProviderInfo("mock", "delay", ProviderType.LOCAL)
        override fun close() {}
    }

    private val DECOMPOSE_JSON =
        "[{\"id\":\"t1\",\"desc\":\"子任务A\",\"criteria\":\"完成A\"}," +
            "{\"id\":\"t2\",\"desc\":\"子任务B\",\"criteria\":\"完成B\"}]"

    private fun engineWith(provider: LlmProvider): Pair<AgentEngine, SessionManager> {
        val sm = SessionManager()
        return AgentEngine(llmProvider = provider, sessionManager = sm) to sm
    }

    private fun runMission(engine: AgentEngine, task: String, maxParallel: Int = 4) = runBlocking {
        engine.runWithMission(task = task, maxStepsPerSubtask = 5, maxRetriesPerSubtask = 2, maxParallel = maxParallel, onStep = {})
    }

    // ── 用例 ─────────────────────────────────────────────────────────

    @Test
    fun `subtasks execute in parallel`() {
        // 2 子任务 × (worker 1 轮 + verifier 1 轮) × 100ms = 400ms 顺序;
        // 并行 (maxParallel=2) ≈ 拆解 100 + 200 + 合成 100 = 400ms —— 用 4 子任务放大差异
        val responses = mutableListOf(
            "[{\"id\":\"t1\",\"desc\":\"子任务A\",\"criteria\":\"完成A\"}," +
                "{\"id\":\"t2\",\"desc\":\"子任务B\",\"criteria\":\"完成B\"}," +
                "{\"id\":\"t3\",\"desc\":\"子任务C\",\"criteria\":\"完成C\"}," +
                "{\"id\":\"t4\",\"desc\":\"子任务D\",\"criteria\":\"完成D\"}]"
        )
        repeat(4) { responses.add("子任务完成") }   // 4 × worker
        repeat(4) { responses.add("VERDICT: PASS\nANALYSIS: ok") }  // 4 × verifier
        responses.add("综合报告")                    // 合成
        val provider = DelayLlmProvider(100, responses)
        val (engine, _) = engineWith(provider)

        val start = System.currentTimeMillis()
        runMission(engine, "并行任务", maxParallel = 4)
        val elapsed = System.currentTimeMillis() - start

        // 顺序需 ≥ 900ms (9 次调用 × 100ms); 并行 ≤ 500ms 左右
        assertTrue("并行应显著快于串行: ${elapsed}ms", elapsed < 700)
        assertTrue("报告应完成: $elapsed", elapsed > 0)
    }

    @Test
    fun `mission workers do not pollute main session`() {
        val provider = ScriptedLlmProvider(listOf(
            DECOMPOSE_JSON,
            "子任务A完成", "VERDICT: PASS\nANALYSIS: 达标",
            "子任务B完成", "VERDICT: PASS\nANALYSIS: 达标",
            "综合报告"
        ))
        val (engine, sm) = engineWith(provider)
        val report = runMission(engine, "两个任务")
        assertTrue(report.startsWith("## Mission:"))
        // 零待命: worker 会话不入主会话、用完即销毁
        assertNull("worker 不应污染 conversationSessionId", engine.currentConversationId())
        assertTrue("worker 会话应零待命销毁", sm.sessions.value.isEmpty())
    }

    @Test
    fun `mission scope blocks memory writes`() = runBlocking {
        val executor = AgentMemoryExecutor()
        val missionCtx = ExecutionContext(sessionId = "mission-test", scope = "mission")
        val r = executor.commands["memory.record"]!!(listOf("测试内容"), missionCtx)
        assertTrue("mission worker 应被屏蔽写记忆: ${r.output}", r.output.contains("不写记忆"))
    }

    @Test
    fun `verifier fail retries exactly maxRetries times`() {
        // 2 次 FAIL 后 PASS: worker 调 3 次、verifier 调 3 次
        val provider = ScriptedLlmProvider(listOf(
            "[{\"id\":\"t1\",\"desc\":\"子任务A\",\"criteria\":\"完成A\"}]",
            "初版结果",
            "VERDICT: FAIL\nANALYSIS: 不达标\nFIX: 重新做",
            "修订结果",
            "VERDICT: FAIL\nANALYSIS: 仍不达标\nFIX: 再改",
            "最终结果",
            "VERDICT: PASS\nANALYSIS: 达标",
            "综合报告"
        ))
        val (engine, _) = engineWith(provider)
        val report = runMission(engine, "重试任务", maxParallel = 1)
        // worker 调用 = 3（含子任务描述的调用，排除合成含 desc）
        val workerCalls = provider.calls.count { it.contains("子任务A") && !it.contains("Synthesize") }
        assertEquals("worker 应恰调 3 次（初派 + 2 重试）", 3, workerCalls)
        assertTrue("FAIL 后最终应成功: $report", report.contains("✅ 1"))
    }

    @Test
    fun `decompose falls back to line parsing`() {
        val provider = ScriptedLlmProvider(listOf(
            "- 任务A | 标准A\n* 任务B | 标准B",
            "A完成", "VERDICT: PASS\nANALYSIS: ok",
            "B完成", "VERDICT: PASS\nANALYSIS: ok",
            "综合报告"
        ))
        val (engine, _) = engineWith(provider)
        val report = runMission(engine, "行解析任务")
        assertTrue("行解析应产出 2 子任务: $report", report.contains("子任务: 2"))
        assertTrue("报告应有合成内容", report.contains("综合报告"))
    }

    @Test
    fun `report structure with verified and failed counts`() {
        // 1 成功 + 1 失败（worker 硬错误耗尽重试）
        val provider = ScriptedLlmProvider(listOf(
            DECOMPOSE_JSON,
            "A完成", "VERDICT: PASS\nANALYSIS: ok",
            "Error: worker 崩溃",
            "Error: worker 崩溃",
            "Error: worker 崩溃",
            "综合报告"
        ))
        val (engine, _) = engineWith(provider)
        // maxParallel=1 串行 — ScriptedLlmProvider 按序回放依赖确定的调用顺序
        val report = runMission(engine, "报告结构", maxParallel = 1)
        assertTrue("报告应以 ## Mission: 开头", report.startsWith("## Mission: 报告结构"))
        assertTrue("应统计 ✅ 1: $report", report.contains("✅ 1"))
        assertTrue("应统计 ❌ 1: $report", report.contains("❌ 1"))
    }
}
