// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Evolution Agent 分析批次测试 (v0.37.3) — 失败累计/进度管理/报告保存/过期清理。
 */
class EvolutionAgentTest {

    private lateinit var tmpBase: File

    @Before
    fun setUp() {
        tmpBase = File(System.getProperty("java.io.tmpdir"), "evo-agent-${System.currentTimeMillis()}")
        tmpBase.mkdirs()
        DataPaths.initialize(tmpBase.absolutePath)
    }

    @After
    fun tearDown() {
        // 临时目录交由系统清理, 不递归删除
    }

    @Test
    fun `失败累计达批次阈值触发分析`() {
        repeat(EvolutionStore.ANALYSIS_BATCH) { i ->
            EvolutionStore.recordFailure("t", "cmd$i", "ERR", "msg$i", "Pipeline")
        }
        assertEquals(5, EvolutionStore.pendingFailureCount("t"))
        assertEquals(5, EvolutionStore.pendingFailures("t").size)
        val runner = EvolutionAgentRunner(FakeLlmProvider("## 结论\n- 模式A"))
        assertTrue("累计 5 条应触发", runner.shouldTrigger("t"))
        EvolutionStore.markAnalyzed("t", 5)
        assertEquals(0, EvolutionStore.pendingFailureCount("t"))
        assertTrue("分析后不再触发", !runner.shouldTrigger("t"))
    }

    @Test
    fun `报告保存带frontmatter且可过期清理`() {
        val path = EvolutionStore.saveReport("t", "## 结论\n- 模式A", 3)
        assertNotNull("报告必须落盘", path)
        val report = File(path!!)
        assertTrue(report.isFile)
        assertTrue("报告必须含 pending 状态", report.readText().contains("status: pending"))
        // 造一个 16 天前的旧报告
        val old = File(report.parentFile, "old_1.md")
        old.writeText("x")
        old.setLastModified(System.currentTimeMillis() - 16L * 24 * 3600 * 1000)
        val deleted = EvolutionStore.cleanupExpiredReports("t")
        assertTrue("过期报告应被删除", deleted >= 1)
    }

    @Test
    fun `buildPrompt注入有限失败上下文`() {
        val runner = EvolutionAgentRunner(FakeLlmProvider("ok"))
        val prompt = runner.buildPrompt(
            "t",
            listOf(
                EvolutionFailure(
                    id = "1", timestamp = 0, agentName = "t",
                    command = "fs.cat", errorCode = "ERR_IO", message = "m",
                    source = "Pipeline", task = "读文件"
                )
            )
        )
        assertTrue(prompt.contains("fs.cat"))
        assertTrue(prompt.contains("金字塔追问"))
        assertTrue(prompt.contains("5-Why"))
        assertTrue(prompt.contains("增量"))
    }

    private class FakeLlmProvider(private val answer: String) : LlmProvider {
        override suspend fun complete(prompt: String) = answer
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
            onToken(answer)
            return answer
        }
        override fun info() = ProviderInfo("test", "test", ProviderType.LOCAL)
        override fun close() {}
    }
}
