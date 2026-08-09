// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.PlanModeExecutor
import com.mengpaw.kernel.PlanStepStatus
import com.mengpaw.kernel.TaskPlan
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** /plan UI 监控测试 (v0.34.3): PlanMonitor 快照/监听 + runWithPlan 发布与清除。 */
class PlanMonitorTest {

    @Before
    fun setup() {
        PlanMonitor.stop()
    }

    private fun plan(steps: Int = 3): TaskPlan = TaskPlan(
        task = "t",
        steps = (1..steps).map { i ->
            com.mengpaw.kernel.PlanStep(i - 1, "Step $i", "sys.battery", "ok")
        }
    )

    @Test
    fun `start publishes full plan snapshot`() {
        PlanMonitor.start(plan(), "agent-x")
        val snap = PlanMonitor.currentSnapshot()
        assertTrue(snap.active)
        assertEquals(3, snap.plan?.steps?.size)
        assertEquals("agent-x", snap.agentName)
        assertTrue(snap.plan!!.steps.all { it.status == PlanStepStatus.PENDING })
    }

    @Test
    fun `updateStep changes status and notifies listener`() {
        var last: PlanSnapshot? = null
        val listener: PlanListener = { last = it }
        PlanMonitor.addListener(listener)
        try {
            PlanMonitor.start(plan(), "agent-x")
            PlanMonitor.updateStep(0, PlanStepStatus.RUNNING)
            assertEquals(PlanStepStatus.RUNNING, last?.plan?.steps?.get(0)?.status)
            PlanMonitor.updateStep(0, PlanStepStatus.COMPLETED)
            assertEquals(PlanStepStatus.COMPLETED, PlanMonitor.currentSnapshot().plan?.steps?.get(0)?.status)
        } finally {
            PlanMonitor.removeListener(listener)
        }
    }

    @Test
    fun `stop clears active`() {
        PlanMonitor.start(plan(), "agent-x")
        assertTrue(PlanMonitor.currentSnapshot().active)
        PlanMonitor.stop()
        assertFalse(PlanMonitor.currentSnapshot().active)
        assertEquals(null, PlanMonitor.currentSnapshot().plan)
    }

    @Test
    fun `concurrent add remove listeners does not crash`() {
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(200) {
                    val l: PlanListener = {}
                    PlanMonitor.addListener(l)
                    PlanMonitor.removeListener(l)
                    PlanMonitor.currentSnapshot()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // 静默通过即可
    }

    @Test
    fun `runWithPlan publishes then clears plan`() = runBlocking {
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "plan-monitor", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> "STEP 1: 查电量 | ACTION: sys.battery | EXPECT: 电量\n" +
                     "STEP 2: 查网络 | ACTION: sys.network | EXPECT: 网络"
                else -> "Final Answer: done"
            }
        }
        val sm = SessionManager()
        val engine = AgentEngine(llmProvider = llm, sessionManager = sm)
        val executor = PlanModeExecutor(engine, engine.getPipelineManager(), sm, engine.getPromptEngine())

        val result = executor.runWithPlan("测试")
        assertTrue("计划执行应完成", result.contains("2/2"))
        assertFalse("计划结束后应清除", PlanMonitor.currentSnapshot().active)
    }
}
