// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * plugin.verify/auto 发现性回归测试 — FIX(自检报告误报根因):
 * 代码完整支持 plugin.verify --all (v0.14.0 起), 但 Agent 所有触达来源
 * (BM25 索引/CLI.md 双表/系统提示词) 全部缺失该命令, 唯一可见点 self.tools
 * 只输出裸命令名 → Agent 被迫盲试 → 报告"自相矛盾"。
 * 本测试锁死: 索引必须存在且 usage 携带 --all 双形态。
 */
class PluginVerifyDiscoverabilityTest {

    @Before
    fun reset() {
        CommandSearch.clear()
    }

    @Test
    fun `buildAll seeds plugin verify with batch usage`() {
        BuiltinCommandIndex.buildAll()
        val hit = CommandSearch.search("校验插件", 10)
            .firstOrNull { it.fullName == "plugin.verify" }
        assertNotNull("self.search 必须能搜到 plugin.verify", hit)
        assertTrue("usage 必须暴露 --all 批量形态", hit!!.usage.contains("--all"))
    }

    @Test
    fun `verify and auto are both discoverable`() {
        BuiltinCommandIndex.buildAll()
        val names = CommandSearch.search("插件", 50).map { it.fullName }.toSet()
        assertTrue("verify 应可被发现", names.contains("plugin.verify"))
        assertTrue("auto 应可被发现", names.contains("plugin.auto"))
    }

    @Test
    fun `natural language phrase hits via cjk bigram window`() {
        BuiltinCommandIndex.buildAll()
        // FIX: 中文无空格分词 — 整词组("批量验证")作 token 漏配, 双字窗口拆出"验证"命中关键词
        val hit = CommandSearch.search("批量验证", 10)
            .firstOrNull { it.fullName == "plugin.verify" }
        assertNotNull("中文'批量验证'应命中 plugin.verify", hit)
    }

    @Test
    fun `multi-word zh query reaches sys-like command`() {
        // 系统性修复回归: 自检报告 P0-1 "用'日历/屏幕/录音'搜不到 sys.*"
        // — 单词可命中, 但"添加日历事件"整词组此前 score=0 完全搜不到
        CommandSearch.registerOrUpdate(
            CommandIndex("sys.calendar.add", "sys", "添加日历事件",
                "sys.calendar.add", zhKeywords = listOf("日历", "添加事件", "日程")))
        val hit = CommandSearch.search("添加日历事件", 5)
            .firstOrNull { it.fullName == "sys.calendar.add" }
        assertNotNull("整词组查询应经双字窗口命中", hit)
    }

    @Test
    fun `two-char zh query keeps original scoring`() {
        // 2 字词不触发窗口拆分 — 原有单词命中格局不变
        CommandSearch.registerOrUpdate(
            CommandIndex("sys.calendar.add", "sys", "添加日历事件",
                "sys.calendar.add", zhKeywords = listOf("日历", "日程")))
        val hit = CommandSearch.search("日历", 5)
            .firstOrNull { it.fullName == "sys.calendar.add" }
        assertNotNull("'日历'应直接命中 zhKeywords", hit)
    }
}
