// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RalphRunner 串行 fresh-agent 迭代回归 (P2-5):
 * - worker 给出 Final Answer 且 LLM 评估通过 → COMPLETE;
 * - 评估不通过 → 继续下一轮, 到 maxRounds 仍不通过 → INCOMPLETE。
 */
class RalphRunnerTest {

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_ralph_test")
    }

    /** worker 始终给出 Final Answer; 评估结果由 [evalYes] 控制。 */
    private class RalphFakeProvider(private val evalYes: Boolean) : LlmProvider {
        override suspend fun complete(prompt: String): String = if (evalYes) "YES" else "NO"
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
            "Final Answer: 完成目标"
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
            onToken("完成目标")
            return "Final Answer: 完成目标"
        }
        override fun info() = ProviderInfo("mock", "ralph", ProviderType.LOCAL)
        override fun close() {}
    }

    @Test
    fun `ralph completes in one round when eval approves`() = runBlocking {
        val engine = AgentEngine(
            llmProvider = RalphFakeProvider(evalYes = true), sessionManager = SessionManager()
        )
        val out = engine.runRalph("写一份目标报告", maxRounds = 3)
        assertEquals(RalphRunner.RalphStatus.COMPLETE, out.status)
        assertEquals("完成目标", out.finalAnswer)
        assertEquals(1, out.roundsUsed)
    }

    @Test
    fun `ralph incomplete after max rounds when eval keeps rejecting`() = runBlocking {
        val engine = AgentEngine(
            llmProvider = RalphFakeProvider(evalYes = false), sessionManager = SessionManager()
        )
        val out = engine.runRalph("写一份目标报告", maxRounds = 2)
        assertEquals(RalphRunner.RalphStatus.INCOMPLETE, out.status)
        assertEquals(2, out.roundsUsed)
    }
}
