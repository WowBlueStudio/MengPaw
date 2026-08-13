// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.GoalModeExecutor
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Goal 模式中断检测单测 (v0.37.3) — LLM 明确表达任务不可完成时应提前中断,
 * 而不是空转到 maxTurns 耗尽。
 */
class GoalModeInterruptTest {

    private class FakeLlmProvider : LlmProvider {
        override suspend fun complete(prompt: String) = "YES"
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
            onToken("YES")
            return "YES"
        }
        override fun info() = ProviderInfo("test", "test", ProviderType.LOCAL)
        override fun close() {}
    }

    private val executor = GoalModeExecutor(AgentEngine(llmProvider = FakeLlmProvider()))

    @Test
    fun `中文明确不可完成表达命中`() {
        val hit = executor.detectImpossible("这个任务无法完成，因为缺少必要的网络权限。")
        assertNotNull("无法完成 应命中", hit)
    }

    @Test
    fun `英文明确不可完成表达命中`() {
        val hit = executor.detectImpossible("I cannot complete this task because the API key is missing.")
        assertNotNull("I cannot complete this task 应命中", hit)
    }

    @Test
    fun `正常完成结果不命中`() {
        assertNull(executor.detectImpossible("任务已完成，结果如下：\n- 数据已保存"))
    }

    @Test
    fun `局部步骤受限但整体继续不误判`() {
        assertNull(
            executor.detectImpossible(
                "虽然无法直接访问数据库，但我可以通过 API 完成查询。任务可以继续。"
            )
        )
    }

    @Test
    fun `中断原因返回匹配短语`() {
        assertEquals("无法完成任务", executor.detectImpossible("无法完成任务，请停止"))
    }

    @Test
    fun `评估结果三态分类`() {
        assertEquals(GoalModeExecutor.GoalEval.SATISFIED, executor.classifyEval("YES 目标已完成"))
        assertEquals(GoalModeExecutor.GoalEval.OFFTRACK, executor.classifyEval("OFFTRACK 执行了无关操作"))
        assertEquals(GoalModeExecutor.GoalEval.OFFTRACK, executor.classifyEval("已偏离目标, 请回到原任务"))
        assertEquals(GoalModeExecutor.GoalEval.NEEDS_REVISION, executor.classifyEval("NO 还差一步"))
    }
}
