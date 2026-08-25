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
    fun `repeat count survives process restart via disk backfill (G2)`() {
        // G2 (v0.44): ensureFailuresLoaded 从磁盘回填 repeatIndex, 跨重启复现数不丢失
        EvolutionStore.resetFailuresForTest()
        EvolutionStore.recordFailure("evo-g2", "fs.cat /a", "ERR_IO", "m", "Pipeline")
        EvolutionStore.recordFailure("evo-g2", "fs.cat /a", "ERR_IO", "m", "Pipeline")
        assertEquals("同进程第 2 次应计 2", 2,
            EvolutionStore.recentFailures("evo-g2", 1).first().repeatCount)
        // 模拟进程重启: 清空内存态 (repeatIndex/buffer/懒加载标记), 保留磁盘
        EvolutionStore.resetFailuresForTest()
        // 重启后再次失败 — 应继续计 3, 而非重置为 1
        EvolutionStore.recordFailure("evo-g2", "fs.cat /a", "ERR_IO", "m", "Pipeline")
        assertEquals("跨重启复现数应继续到 3", 3,
            EvolutionStore.recentFailures("evo-g2", 1).first().repeatCount)
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

    // ── 回合内重试循环停指令 (2026-08-08, 对齐 QwenPaw RETRY LOOP DETECTED) ──

    @Test
    fun `retry loop directive triggers at threshold and only once`() {
        val cmd = "agent.read missing.md"
        val code = "ERR_NOT_FOUND"
        // 阈值前不触发
        assertNull("1 次失败不触发", EvolutionStore.retryLoopDirective(cmd, code, 1, false))
        assertNull("2 次失败不触发", EvolutionStore.retryLoopDirective(cmd, code, 2, false))
        // 满 3 次触发
        val directive = EvolutionStore.retryLoopDirective(cmd, code, 3, false)
        assertNotNull("满 3 次应触发", directive)
        assertTrue("应含重试循环标识", directive!!.contains("重试循环"))
        assertTrue("应要求停止重试", directive.contains("停止重试"))
        assertTrue("应提供换方法选项", directive.contains("根本不同的方法"))
        assertTrue("应提供向用户说明选项", directive.contains("向用户如实说明"))
        // 已注入过不再重复 (防刷屏)
        assertNull("已注入不重复", EvolutionStore.retryLoopDirective(cmd, code, 4, true))
    }

    @Test
    fun `retry loop directive survives malformed input`() {
        assertNull("空命令不触发", EvolutionStore.retryLoopDirective("", "", 5, false))
        assertNull("负计数不触发", EvolutionStore.retryLoopDirective("fs.cat x", "ERR_IO", -1, false))
    }

    // ── 失败截断进化记录 (2026-08-08): 上下文片段剪取 ──

    @Test
    fun `termination records context snippet into failure archive`() {
        EvolutionStore.recordTermination(
            "evo-term-1", "max_steps", "agent.read missing.md", "ERR_NOT_FOUND",
            "[user] 读取文件\n[assistant] Thought: 读取文件\n[assistant] Command: agent.read missing.md\nResult: Error [ERR_NOT_FOUND]")
        val text = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("evo-term-1")).readText()
        assertTrue("应含截断原因: $text", text.contains("max_steps"))
        assertTrue("应含上下文片段标记: $text", text.contains("会话上下文片段"))
        assertTrue("应剪取到失败命令上下文: $text", text.contains("agent.read missing.md"))
    }

    @Test
    fun `termination with empty command uses reason as pattern key`() {
        EvolutionStore.recordTermination("evo-term-2", "max_steps", "", "", "[user] 任务")
        val text = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("evo-term-2")).readText()
        assertTrue("空命令应以终止原因为模式键: $text", text.contains("(终止: max_steps)"))
    }

    @Test
    fun `termination with blank reason and context is skipped`() {
        assertNull("空 reason 且空上下文不记录", EvolutionStore.recordTermination("evo-term-3", "", "", "", ""))
    }

    // ── v2 进化产物: 去重 / 懒加载 / 上下文 / 指令集持久化 (2026-08-09) ──

    @Test
    fun `failure archive dedups same pattern to single line`() {
        EvolutionStore.resetFailuresForTest()
        EvolutionStore.recordFailure("evo-v2-dedup", "agent.read x", "ERR_NOT_FOUND", "第一次失败", "Pipeline")
        EvolutionStore.recordFailure("evo-v2-dedup", "agent.read x", "ERR_NOT_FOUND", "第二次失败", "Pipeline")
        EvolutionStore.recordFailure("evo-v2-dedup", "agent.read x", "ERR_NOT_FOUND", "第三次失败", "Pipeline")
        val lines = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("evo-v2-dedup"))
            .readLines().filter { it.isNotBlank() }
        assertEquals("同模式应只保留一行", 1, lines.size)
        assertTrue("repeatCount 应累计到 3: $lines", lines[0].contains("\"repeatCount\":3"))
        val stats = EvolutionStore.stats("evo-v2-dedup")
        assertTrue("统计应显示累计次数: $stats", stats.contains("累计 3 次失败"))
        assertTrue("统计应显示 1 条模式: $stats", stats.contains("1 条模式"))
    }

    @Test
    fun `failure archive dedups by command name across different params`() {
        // v3 (2026-08-09 真实数据): 同命令不同参数/不同 Thought 必须合并 — 实测 termux.run 9 次各成行
        EvolutionStore.resetFailuresForTest()
        EvolutionStore.recordFailure("evo-v3-name", "termux.run echo a", "ERR_NOT_FOUND", "x", "Pipeline")
        EvolutionStore.recordFailure("evo-v3-name", "termux.run cat -A f.md\n\nResult: <untrusted_data>\n第一行$", "ERR_NOT_FOUND", "y", "Pipeline")
        EvolutionStore.recordFailure("evo-v3-name", "termux.run grep -c f.md", "ERR_NOT_FOUND", "z", "Pipeline")
        val lines = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("evo-v3-name"))
            .readLines().filter { it.isNotBlank() }
        assertEquals("同命令不同参数应合并为一行", 1, lines.size)
        assertTrue("repeatCount 应累计 3: $lines", lines[0].contains("\"repeatCount\":3"))
        // 命令字段应清洗为单行 (剥离 Thought/Observation 污染)
        assertFalse("命令字段不得含换行: $lines", lines[0].contains("\\n"))
        assertTrue("命令字段应保留命令名: $lines", lines[0].contains("termux.run"))
    }

    @Test
    fun `multiline polluted command is sanitized on load`() {
        // 模拟旧版污染数据 (command 含完整 Thought+Observation 多行) → 懒加载合并后单行
        EvolutionStore.resetFailuresForTest()
        val agent = "evo-v3-sanitize"
        EvolutionStore.recordFailure(agent, "fs.ls /data/x\n\n看看目录\n\nResult: <untrusted_data>\nfiles/\n├── a.md", "ERR_NOT_FOUND", "m", "Pipeline")
        EvolutionStore.recordFailure(agent, "fs.ls /data/y\n\n再次查看", "ERR_NOT_FOUND", "m2", "Pipeline")
        val lines = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile(agent))
            .readLines().filter { it.isNotBlank() }
        assertEquals("同命令名应合并为一行", 1, lines.size)
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<com.mengpaw.kernel.evolution.EvolutionFailure>(lines[0])
        assertFalse("存储命令应为单行: ${parsed.command}", parsed.command.contains("\n"))
        assertTrue("应保留命令名: ${parsed.command}", parsed.command.startsWith("fs.ls"))
    }

    @Test
    fun `failure archive survives process restart via lazy load`() {
        EvolutionStore.resetFailuresForTest()
        EvolutionStore.recordFailure("evo-v2-persist", "fs.cat y", "ERR_IO", "读盘失败", "Pipeline")
        EvolutionStore.recordFailure("evo-v2-persist", "fs.cat y", "ERR_IO", "再次读盘失败", "Pipeline")
        // 模拟进程重启: 清空内存态 (buffer + 懒加载标记) → 统计/复现必须从文件恢复
        EvolutionStore.resetFailuresForTest()
        val stats = EvolutionStore.stats("evo-v2-persist")
        assertTrue("重启后统计应从文件恢复: $stats", stats.contains("累计 2 次失败"))
        val repeated = EvolutionStore.repeatedPatterns("evo-v2-persist")
        assertEquals("重启后复现模式应恢复", 1, repeated.size)
    }

    @Test
    fun `failure record carries task session and context for traceability`() {
        EvolutionStore.resetFailuresForTest()
        EvolutionStore.recordFailure(
            "evo-v2-ctx", "agent.write a.md", "ERR_PERMISSION_DENIED", "无权限",
            "Pipeline", task = "帮我把报告写到文件", sessionId = "sess-42",
            contextSnippet = "[user] 写报告\n[assistant] Thought: 写入文件")
        val text = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("evo-v2-ctx")).readText()
        assertTrue("应含任务字段: $text", text.contains("帮我把报告写到文件"))
        assertTrue("应含会话 id: $text", text.contains("sess-42"))
        assertTrue("应含上下文片段: $text", text.contains("Thought: 写入文件"))
        val stats = EvolutionStore.stats("evo-v2-ctx")
        assertTrue("audit 应展示任务: $stats", stats.contains("帮我把报告写到文件"))
    }

    @Test
    fun `learned commands persist and restore into search index`() {
        EvolutionStore.resetFailuresForTest()
        val cmd = com.mengpaw.kernel.cli.CommandIndex(
            fullName = "agent.memory.keep",
            namespace = "agent",
            description = "写入长期记忆 (用户学习修正: 必须带内容)",
            usage = "agent.memory.keep <内容>",
            zhKeywords = listOf("记住", "沉淀", "教训"),
            enKeywords = listOf("remember", "lesson")
        )
        EvolutionStore.saveLearnedCommand(cmd)
        // 模拟重启: 从文件恢复
        EvolutionStore.resetFailuresForTest()
        EvolutionStore.restoreLearnedCommands()
        val results = com.mengpaw.kernel.cli.CommandSearch.search("沉淀教训", 3)
        assertTrue("重启后 learn.command 登记应可检索: $results",
            results.any { it.fullName == "agent.memory.keep" })
    }

    @Test
    fun `has evolution data reflects failures and learned commands`() {
        EvolutionStore.resetFailuresForTest()
        val agent = "evo-v2-hasdata"
        assertFalse("无数据应返回 false", EvolutionStore.hasEvolutionData(agent))
        EvolutionStore.recordFailure(agent, "fs.cat x", "ERR_IO", "m", "Pipeline")
        assertTrue("有失败档案应返回 true", EvolutionStore.hasEvolutionData(agent))
        // hasEvolutionData 查文件, 不依赖内存 buffer — 清内存后文件仍在, 仍为 true
        EvolutionStore.resetFailuresForTest()
        assertTrue("清空内存后文件仍在 → 仍为 true", EvolutionStore.hasEvolutionData(agent))
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
