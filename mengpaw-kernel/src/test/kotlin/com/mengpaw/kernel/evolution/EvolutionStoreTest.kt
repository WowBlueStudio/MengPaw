// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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

    // ── P0/P1 (2026-08-08 自检): 幻觉率 + 复现强制处理提醒 + 红灯 ──

    @Test
    fun `recurrence reminder triggers on unrepaired repeat`() {
        EvolutionStore.recordFailure("evo-p1-a", "agent.read", "ERR_NOT_FOUND", "路径不存在", "Pipeline")
        assertNull("单次失败不提醒", EvolutionStore.recurrenceReminder("evo-p1-a", "agent.read", "ERR_NOT_FOUND"))
        EvolutionStore.recordFailure("evo-p1-a", "agent.read", "ERR_NOT_FOUND", "路径不存在", "Pipeline")
        val reminder = EvolutionStore.recurrenceReminder("evo-p1-a", "agent.read", "ERR_NOT_FOUND")
        assertNotNull("复现 2 次未修正应强制提醒", reminder)
        assertTrue("提醒应含复现次数", reminder!!.contains("复现 2 次"))
        assertTrue("提醒应含二选一动作", reminder.contains("evolution.learn.command") && reminder.contains("agent.memory.keep"))
    }

    @Test
    fun `recurrence reminder suppressed after correction`() {
        val f = EvolutionStore.recordFailure("evo-p1-b", "fs.cat", "ERR_NOT_FOUND", "x", "Pipeline")
        EvolutionStore.recordFailure("evo-p1-b", "fs.cat", "ERR_NOT_FOUND", "x", "Pipeline")
        EvolutionStore.markCorrected("evo-p1-b", f.id)
        assertNull("已沉淀修正后不再强制", EvolutionStore.recurrenceReminder("evo-p1-b", "fs.cat", "ERR_NOT_FOUND"))
    }

    @Test
    fun `session outcome counts unmentioned failures as hallucination risk`() {
        // 失败但 Final Answer 未提及错误 → 计入未如实提及
        EvolutionStore.recordSessionOutcome("evo-p0-a",
            listOf("agent.write a.md" to "ERR_NOT_FOUND"),
            "文件已成功写入, 内容完整")
        val stats = EvolutionStore.veracityStats("evo-p0-a")
        assertTrue("应显示未如实提及 1 条", stats.contains("未如实提及 1 条"))
        assertTrue("应显示如实提及 0/1", stats.contains("0/1"))
    }

    @Test
    fun `session outcome counts mentioned failures as honest`() {
        // Final Answer 含错误码 → 如实提及
        EvolutionStore.recordSessionOutcome("evo-p0-b",
            listOf("agent.write a.md" to "ERR_NOT_FOUND"),
            "写入失败: Error [ERR_NOT_FOUND], 路径不存在")
        val stats = EvolutionStore.veracityStats("evo-p0-b")
        assertTrue("含错误码应视为如实提及", stats.contains("1/1"))
        assertTrue("不应有未提及", !stats.contains("未如实提及 1"))
    }

    @Test
    fun `stats shows red light when failures exist but none corrected`() {
        EvolutionStore.recordFailure("evo-p1-red", "agent.read", "ERR_NOT_FOUND", "m", "Pipeline")
        val stats = EvolutionStore.stats("evo-p1-red")
        assertTrue("0 沉淀应显示红灯", stats.contains("红灯"))
        assertTrue("红灯应指向处理动作", stats.contains("agent.memory.keep"))
    }

    @Test
    fun `veracity persists to jsonl and reloads across process restart`() {
        EvolutionStore.resetVeracityForTest()
        // 第一次会话: 未提及失败
        EvolutionStore.recordSessionOutcome("evo-p0-persist",
            listOf("agent.write a.md" to "ERR_NOT_FOUND"),
            "文件已成功写入")
        // 落盘断言
        val file = File(com.mengpaw.kernel.DataPaths.evolutionVeracityFile("evo-p0-persist"))
        assertTrue("veracity.jsonl 应落盘", file.exists())
        assertTrue("落盘内容应含记录", file.readText().contains("totalFailures"))
        // 模拟进程重启: 清内存与加载标志 → 从文件重新累计
        EvolutionStore.resetVeracityForTest()
        val stats = EvolutionStore.veracityStats("evo-p0-persist")
        assertTrue("重启后应能从文件恢复统计", stats.contains("0/1"))
    }

    @Test
    fun `recurrence reminder escalates at three repeats`() {
        EvolutionStore.recordFailure("evo-p1-escalate", "agent.ls", "ERR_NOT_FOUND", "x", "Pipeline")
        EvolutionStore.recordFailure("evo-p1-escalate", "agent.ls", "ERR_NOT_FOUND", "x", "Pipeline")
        EvolutionStore.recordFailure("evo-p1-escalate", "agent.ls", "ERR_NOT_FOUND", "x", "Pipeline")
        val reminder = EvolutionStore.recurrenceReminder("evo-p1-escalate", "agent.ls", "ERR_NOT_FOUND")
        assertNotNull(reminder)
        assertTrue("复现 3 次应升级为强制措辞", reminder!!.contains("🚨"))
        assertTrue("升级版应禁止继续同类操作", reminder.contains("不得继续"))
    }

    // ── P0 实质化 (2026-08-08): Final Answer 门禁纯函数 ──

    @Test
    fun `failure mentioned by error code is honest`() {
        assertTrue(EvolutionStore.isFailureMentioned(
            "写入失败: Error [ERR_NOT_FOUND], 路径不存在", "agent.write a.md", "ERR_NOT_FOUND"))
        assertTrue(EvolutionStore.isFailureMentioned(
            "agent.read 读取失败", "agent.read profile.md", "ERR_NOT_FOUND"))
        assertFalse(EvolutionStore.isFailureMentioned(
            "文件已成功写入, 内容完整", "agent.write a.md", "ERR_NOT_FOUND"))
    }

    @Test
    fun `natural language failure phrases are recognized`() {
        // 静默门禁引导自然语言汇报后, 高频口语化失败表述必须命中 (2026-08-08 扩充词表)
        assertTrue("没成功", EvolutionStore.isFailureMentioned(
            "文件写入没成功", "agent.write a.md", "ERR_NOT_FOUND"))
        assertTrue("未成功", EvolutionStore.isFailureMentioned(
            "文件写入未成功", "agent.write a.md", "ERR_NOT_FOUND"))
        assertTrue("报错", EvolutionStore.isFailureMentioned(
            "读取时报错了", "agent.read a.md", "ERR_NOT_FOUND"))
        assertTrue("没能", EvolutionStore.isFailureMentioned(
            "没能写入文件", "agent.write a.md", "ERR_NOT_FOUND"))
        assertTrue("未完成", EvolutionStore.isFailureMentioned(
            "文件读取未完成", "agent.read a.md", "ERR_NOT_FOUND"))
        assertTrue("小写 error", EvolutionStore.isFailureMentioned(
            "an error occurred while writing", "agent.write a.md", "ERR_NOT_FOUND"))
    }

    @Test
    fun `unmentioned failures gate the final answer`() {
        val failures = listOf(
            "agent.write a.md" to "ERR_NOT_FOUND",
            "fs.cat x" to "ERR_PERMISSION_DENIED"
        )
        // 错误码精确绑定 → 放行
        assertTrue(EvolutionStore.unmentionedFailures(
            "写入失败 Error [ERR_NOT_FOUND], 读取 Error [ERR_PERMISSION_DENIED]", failures).isEmpty())
        // 自然语言承认失败 (2026-08-08 放宽: 任一失败词即视为已承认, 不再要求命令名)
        assertTrue("自然语言承认失败应放行", EvolutionStore.unmentionedFailures(
            "部分操作未能完成, 请检查。", failures).isEmpty())
        // 声称成功且无任何失败词 → 全部拦截 (防虚假成功)
        assertEquals(2, EvolutionStore.unmentionedFailures("任务全部完成, 文件已生成。", failures).size)
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

    // ── 无主档案路径 (v0.34.x: 归 进化档案/, 不入 Agent文档/ 防误判假 Agent) ──

    @Test
    fun `unowned failure lands in evolution dir not Agent文档`() {
        EvolutionStore.recordFailure(null, "fs.cat", "ERR_NOT_FOUND", "unowned", "Pipeline")
        val file = File(com.mengpaw.kernel.DataPaths.EVOLUTION, "failures.jsonl")
        assertTrue("无主档案应落 {BASE}/进化档案/failures.jsonl", file.exists())
        assertFalse("Agent文档/ 下不得创建 default 目录", File(com.mengpaw.kernel.DataPaths.AGENTS, "default").exists())
    }

    @Test
    fun `default reserved agent name maps to evolution dir`() {
        EvolutionStore.recordCorrection(EvolutionStore.DEFAULT_AGENT, "不对", "ctx", "task")
        val f = File(com.mengpaw.kernel.DataPaths.EVOLUTION, "reactions.md")
        assertTrue("default 保留字档案应归进化档案/reactions.md", f.exists())
        assertFalse("default 不得写入 Agent文档/", File(com.mengpaw.kernel.DataPaths.AGENTS, "default").exists())
    }

    // ── 旧版 default 目录迁移 (v0.34.x) ──

    @Test
    fun `migrateLegacyDefaultDir moves archive and removes fake workspace`() {
        val base = File(System.getProperty("java.io.tmpdir"), "mengpaw-evo-migrate-${System.currentTimeMillis()}")
        base.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)

        // 构造旧版结构: Agent文档/default/evolution/failures.jsonl + 被误 bootstrap 的模板
        val legacyDefault = File(base, "Agent文档/default")
        val legacyEvo = File(legacyDefault, "evolution")
        legacyEvo.mkdirs()
        File(legacyEvo, "failures.jsonl").writeText("{legacy-archive}")
        File(legacyDefault, "cli.md").writeText("# fake cli")
        File(legacyDefault, "soul.md").writeText("fake soul")

        EvolutionStore.migrateLegacyDefaultDir()

        assertFalse("default 目录应被整体移除", legacyDefault.exists())
        assertTrue("旧失败档案应迁移到 进化档案/",
            File(com.mengpaw.kernel.DataPaths.EVOLUTION, "failures.jsonl").exists())
        assertFalse("误生成的模板不得随档案迁移",
            File(com.mengpaw.kernel.DataPaths.EVOLUTION, "cli.md").exists())
    }

    @Test
    fun `migrate is idempotent and never overwrites newer data`() {
        val base = File(System.getProperty("java.io.tmpdir"), "mengpaw-evo-migrate2-${System.currentTimeMillis()}")
        base.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)

        File(com.mengpaw.kernel.DataPaths.EVOLUTION).mkdirs()
        File(com.mengpaw.kernel.DataPaths.EVOLUTION, "failures.jsonl").writeText("newer-data")
        val legacyEvo = File(base, "Agent文档/default/evolution")
        legacyEvo.mkdirs()
        File(legacyEvo, "failures.jsonl").writeText("legacy-data")

        EvolutionStore.migrateLegacyDefaultDir()
        EvolutionStore.migrateLegacyDefaultDir() // 幂等

        assertEquals("新数据不得被旧档案覆盖", "newer-data",
            File(com.mengpaw.kernel.DataPaths.EVOLUTION, "failures.jsonl").readText())
        assertFalse("迁移后 default 目录不存在", File(base, "Agent文档/default").exists())
    }
}
