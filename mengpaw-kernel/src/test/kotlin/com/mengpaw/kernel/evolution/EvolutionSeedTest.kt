// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 进化种子库 + 复现缺陷检测 单元测试 (P1-4)。
 * - 种子库: 内置新手常见错误 ≥5 条; 命令前缀命中时失败记录 message 自动附教训提示;
 *   evolution.audit (stats) 输出"常见错误预防清单"。
 * - 复现检测: 已沉淀修正 (markCorrected) 的同前缀错误仍复发 ≥2 次 → 自动升级为框架缺陷,
 *   feedback 目录 md 落盘 + 失败记录 message 附提示。
 * 注意: EvolutionStore 是全局 object, 测试间共享缓冲 — agent 名全部唯一。
 */
class EvolutionSeedTest {

    private fun ensureDataPaths() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-seed-test-${System.currentTimeMillis()}")
        tmp.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(tmp.absolutePath)
    }

    @Before
    fun setUp() { ensureDataPaths() }

    @Test
    fun `seed library ships at least five common mistakes with lessons`() {
        assertTrue("种子库应 ≥5 条, 实际 ${EvolutionStore.SEED_PATTERNS.size}", EvolutionStore.SEED_PATTERNS.size >= 5)
        EvolutionStore.SEED_PATTERNS.forEach { seed ->
            assertTrue("种子#${seed.id} 应有命中前缀", seed.prefixes.isNotEmpty())
            assertTrue("种子#${seed.id} 应有错误描述", seed.description.isNotBlank())
            assertTrue("种子#${seed.id} 应有教训", seed.lesson.isNotBlank())
        }
    }

    @Test
    fun `seed hint attaches to failure message when command prefix matches`() {
        val f = EvolutionStore.recordFailure("seed-test-1", "cat", "ERR_NOT_FOUND", "路径不存在", "Pipeline")
        assertTrue("命中种子应附教训提示: ${f.message}", f.message.contains("命中内置种子模式 #1"))
        assertTrue("教训应含定向读取引导 (grep/head/tail)", f.message.contains("grep"))
    }

    @Test
    fun `no seed hint for non-seed command`() {
        val f = EvolutionStore.recordFailure("seed-test-2", "plugin.install", "ERR_IO", "网络错误", "Pipeline")
        assertFalse(f.message.contains("命中内置种子模式"))
    }

    @Test
    fun `stats lists built-in seed checklist`() {
        EvolutionStore.recordFailure("seed-test-3", "echo", "ERR_IO", "写盘失败", "Pipeline")
        val stats = EvolutionStore.stats("seed-test-3")
        assertTrue("audit 应列常见错误预防清单", stats.contains("常见错误预防清单"))
        assertTrue("应标注内置预防种子", stats.contains("内置预防种子"))
        assertTrue("应含种子教训", stats.contains("读回验证"))
        assertTrue("应列出种子命令前缀", stats.contains("echo"))
    }

    @Test
    fun `recurrence after correction auto-upgrades to framework defect`() {
        // 第一次失败 + 沉淀修正 → 不触发
        val first = EvolutionStore.recordFailure("seed-test-4", "agent.memory.keep", "ERR_INVALID_INPUT", "缺参数", "Pipeline")
        assertFalse("首次失败不应触发缺陷检测: ${first.message}", first.message.contains("框架缺陷"))
        assertTrue(EvolutionStore.markCorrected("seed-test-4", first.id))
        // 修正后同型错误再犯 (第 2 次) → 自动升级框架缺陷 + feedback 落盘
        val second = EvolutionStore.recordFailure("seed-test-4", "agent.memory.keep", "ERR_INVALID_INPUT", "修正后仍复发", "Pipeline")
        assertTrue("复发提示应入失败记录 message: ${second.message}", second.message.contains("框架缺陷"))
        val dir = File(com.mengpaw.kernel.DataPaths.evolutionFeedbackDir("seed-test-4"))
        val files = dir.listFiles()?.filter { it.name.endsWith(".md") } ?: emptyList()
        assertTrue("框架缺陷反馈应落盘", files.isNotEmpty())
        assertTrue("反馈应描述失败命令", files.first().readText().contains("agent.memory.keep"))
    }

    @Test
    fun `single failure never triggers framework defect`() {
        val f = EvolutionStore.recordFailure("seed-test-5", "agent.memory.mid", "ERR_NOT_FOUND", "日期分片缺失", "Pipeline")
        assertFalse("单次失败不应触发缺陷检测: ${f.message}", f.message.contains("框架缺陷"))
    }
}
