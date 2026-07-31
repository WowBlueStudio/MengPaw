// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.evolution

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * EvolutionStore 单元测试 — 失败模式匹配/用户反应落盘/绩效统计。
 * 注意: EvolutionStore 是全局 object, 测试间共享缓冲 — 断言用相对性质 (contains/any)。
 */
class EvolutionStoreTest {

    private fun ensureDataPaths() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-evo-test-${System.currentTimeMillis()}")
        tmp.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(tmp.absolutePath)
    }

    @Before
    fun setUp() { ensureDataPaths() }

    @Test
    fun `same command and errorCode increments repeatCount`() {
        val first = EvolutionStore.recordFailure("evo-test-1", "fs.cat", "ERR_NOT_FOUND", "file missing", "Pipeline")
        val second = EvolutionStore.recordFailure("evo-test-1", "fs.cat", "ERR_NOT_FOUND", "file missing again", "Pipeline")
        assertEquals(1, first.repeatCount)
        assertEquals(2, second.repeatCount)

        val repeated = EvolutionStore.repeatedPatterns("evo-test-1", 5)
        assertTrue("复现模式应包含 fs.cat", repeated.any { it.command == "fs.cat" && it.repeatCount >= 2 })
    }

    @Test
    fun `different commands are independent patterns`() {
        val a = EvolutionStore.recordFailure("evo-test-2", "fs.cat", "ERR_NOT_FOUND", "x", "Pipeline")
        val b = EvolutionStore.recordFailure("evo-test-2", "fs.ls", "ERR_NOT_FOUND", "y", "Pipeline")
        assertEquals(1, a.repeatCount)
        assertEquals(1, b.repeatCount)
    }

    @Test
    fun `markCorrected flags failure for performance close`() {
        val f = EvolutionStore.recordFailure("evo-test-3", "net.get", "ERR_TIMEOUT", "timeout", "Pipeline")
        assertTrue(EvolutionStore.markCorrected("evo-test-3", f.id))
        assertTrue(EvolutionStore.stats("evo-test-3").contains("已沉淀修正: 1"))
        // 未知 id 不误标
        assertFalse(EvolutionStore.markCorrected("evo-test-3", "evo_unknown"))
    }

    @Test
    fun `correction lands in reactions archive`() {
        EvolutionStore.recordCorrection("evo-test-4", "不对, 你理解错了", "上一条 Agent 回复摘要", "用户任务")
        val text = EvolutionStore.reactionsText("evo-test-4")
        assertTrue(text.contains("不对"))
        assertTrue(text.contains("上一条 Agent 回复摘要"))
    }

    @Test
    fun `stats renders performance report`() {
        EvolutionStore.recordFailure("evo-test-5", "agent.memory.keep", "ERR_INVALID_INPUT", "usage error", "Pipeline")
        val stats = EvolutionStore.stats("evo-test-5")
        assertTrue(stats.contains("进化绩效"))
        assertTrue(stats.contains("记录失败"))
    }

    @Test
    fun `guide fragment grades deep on repeat failure`() {
        EvolutionStore.recordFailure("evo-test-6", "fs.write", "ERR_IO", "disk full", "Pipeline")
        EvolutionStore.recordFailure("evo-test-6", "fs.write", "ERR_IO", "disk full again", "Pipeline")
        // 分级基于最新失败记录 — 最新是 fs.write 第 2 次 → 深引导
        val deep = EvolutionGuide.buildFragment("evo-test-6", "fs.write", "disk full again")
        assertNotNull(deep)
        assertTrue("深引导应含金字塔四层", deep!!.contains("L1 事实") && deep.contains("L4 进化"))
        assertTrue("深引导应含四分法处置", deep.contains("agent.memory.keep"))

        // 轻失败: 新 agent 单次失败 → 轻引导
        EvolutionStore.recordFailure("evo-test-6b", "fs.cat", "ERR_NOT_FOUND", "boom", "Pipeline")
        val light = EvolutionGuide.buildFragment("evo-test-6b", "fs.cat", "boom")
        assertNotNull(light)
        assertTrue("轻引导应简短", !light!!.contains("L1 事实"))
    }

    @Test
    fun `session brief only when repeated patterns exist`() {
        EvolutionStore.recordFailure("evo-test-7", "fs.cat", "ERR_NOT_FOUND", "m", "Pipeline")
        EvolutionStore.recordFailure("evo-test-7", "fs.cat", "ERR_NOT_FOUND", "m2", "Pipeline")
        val brief = EvolutionGuide.buildSessionBrief("evo-test-7")
        assertNotNull(brief)
        assertTrue(brief!!.contains("复现失败模式"))

        val empty = EvolutionGuide.buildSessionBrief("evo-test-none")
        assertNull("无复现模式时不注入", empty)
    }
}
