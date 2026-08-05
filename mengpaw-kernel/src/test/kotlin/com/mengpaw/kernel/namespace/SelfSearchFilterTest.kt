// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.CommandIndex
import com.mengpaw.kernel.cli.CommandRegistry
import com.mengpaw.kernel.cli.CommandSearch
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * self.search 可用性过滤测试 — FIX(自检报告 P0-1):
 * 索引含静态种子 (BuiltinCommandIndex) 与动态注册条目, 种子命中但注册表不存在的
 * 命令 (插件未安装/停用) 搜索时不再外泄, Agent 不会走上必然失败的行为路径。
 */
class SelfSearchFilterTest {

    @Before
    fun reset() {
        CommandSearch.clear()
        SelfExecutor.commandRegistry = null
    }

    @Test
    fun `search filters out indexed commands missing from registry`() = runTest {
        // 静态种子: 命中但注册表不存在的命令 (模拟插件未激活)
        CommandSearch.registerOrUpdate(
            CommandIndex("skill.run", "skill", "运行技能", "skill.run <技能>"))
        // 动态注册: 真实存在的命令
        CommandSearch.registerOrUpdate(
            CommandIndex("sys.calendar.add", "sys", "添加日历事件", "sys.calendar.add",
                zhKeywords = listOf("日历", "事件"), enKeywords = listOf("calendar")))
        val reg = CommandRegistry()
        reg.register("sys.calendar.add") { _, _ -> ExecutionResult.ok("ok") }
        SelfExecutor.commandRegistry = reg

        val ctx = ExecutionContext(sessionId = "t", agentName = "t")
        val r = SelfExecutor.commands["search"]!!(listOf("日历"), ctx)
        assertTrue(r.success)
        assertTrue("搜索结果应含可用命令", r.output.contains("sys.calendar.add"))
        assertFalse("种子命中但不可执行的命令应被过滤", r.output.contains("skill.run"))
    }

    @Test
    fun `search without registry returns all indexed results`() = runTest {
        // 注册表未接线 (极端场景) — 退回不过滤
        CommandSearch.registerOrUpdate(
            CommandIndex("self.status", "self", "运行状态", "self.status"))
        val ctx = ExecutionContext(sessionId = "t", agentName = "t")
        val r = SelfExecutor.commands["search"]!!(listOf("状态"), ctx)
        assertTrue(r.success)
        assertTrue(r.output.contains("self.status"))
    }
}
