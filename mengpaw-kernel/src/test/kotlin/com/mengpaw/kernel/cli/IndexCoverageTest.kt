// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.agent.AgentDocManager
import com.mengpaw.kernel.agent.AgentExecutor
import com.mengpaw.kernel.namespace.SelfExecutor
import com.mengpaw.kernel.plugin.PluginExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.PipelineManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 索引覆盖度锁死测试 — 发现性铁律 (v0.31.0):
 * "代码存在 ≠ Agent 可触达"。内核每个真实注册的命令必须同时出现在 BM25 静态种子
 * (BuiltinCommandIndex) — 否则 self.search 搜不到, Agent 只能盲试 (plugin.verify 教训)。
 * 用 PipelineManager 真实注册链 (buildPipeline → SelfExecutor.commandRegistry) 做差集,
 * 新增命令忘补索引时本测试立即失败。
 *
 * 边界: sys.* 由 SysExecutor 动态补种 (Android 反射实现, 非静态种子), framework.*
 * 由捆绑插件 registerSearchIndex 动态注册 — 均为设计内机制, 不在本测试范围。
 */
class IndexCoverageTest {

    @Before
    fun reset() {
        CommandSearch.clear()
        SelfExecutor.commandRegistry = null
    }

    private fun buildRegistry(): CommandRegistry {
        val pm = PluginManager()
        PipelineManager(pm, PluginExecutor(pm), AgentExecutor(AgentDocManager())).buildPipeline()
        return SelfExecutor.commandRegistry ?: error("buildPipeline 未暴露 registry")
    }

    @Test
    fun `every registered kernel command is indexed`() {
        BuiltinCommandIndex.buildAll()
        val indexed = CommandSearch.all().map { it.fullName }.toSet()
        val registered = buildRegistry().list()
        val missing = registered.filter { it !in indexed }
        assertTrue(
            "实现有但无索引 — Agent 无法触达: ${missing.joinToString()}",
            missing.isEmpty()
        )
    }

    @Test
    fun `index has no ghost commands for kernel namespaces`() {
        // 反向: 种子有但注册表无 — 可用性过滤虽不外泄, 但提示词/文档若引用即成幽灵引导
        BuiltinCommandIndex.buildAll()
        val indexed = CommandSearch.all().map { it.fullName }.toSet()
        val registered = buildRegistry().list().toSet()
        // 设计内动态机制: framework.* (捆绑插件) + sys.* (core 补种)
        val ghosts = indexed - registered
        val unexpected = ghosts.filter { !it.startsWith("framework.") && !it.startsWith("sys.") }
        assertTrue(
            "索引有但注册表无 (幽灵): ${unexpected.joinToString()}",
            unexpected.isEmpty()
        )
    }

    @Test
    fun `cli doc dynamic agent table covers all commands`() {
        // 动态表数据源注入: AgentExecutor 构造时把注册键集注入 docManager
        val docManager = AgentDocManager()
        AgentExecutor(docManager)
        assertEquals(
            "registeredAgentCommands 应为全部注册键",
            AgentExecutor(AgentDocManager()).commands.keys.sorted(),
            docManager.registeredAgentCommands.sorted()
        )
    }

    @Test
    fun `memory keywords converge to track entries`() {
        // P2-10: memory.* 20+ 子命令曾稀释 BM25 — self.search "记忆" 返回整页近义词条目。
        // 三轨入口化后: 通用"记忆"查询只回 3 个轨道入口 + 核心 memory/search/stats (≤6 条)。
        BuiltinCommandIndex.buildAll()
        val hits = CommandSearch.search("记忆", 20)
        assertTrue(
            "记忆 查询应收敛到 ≤6 条, 实际 ${hits.size}: ${hits.map { it.fullName }}",
            hits.size <= 6
        )
        val names = hits.map { it.fullName }.toSet()
        assertTrue("应含长期记忆入口", names.contains("agent.memory.keep"))
        assertTrue("应含中期记忆入口", names.contains("agent.memory.mid"))
        assertTrue("应含项目记忆入口", names.contains("agent.memory.project"))
        assertTrue(
            "应含核心入口 (memory/search/stats), 实际 $names",
            names.containsAll(setOf("agent.memory", "agent.memory.search", "agent.memory.stats"))
        )
        // 子命令不参与通用查询稀释
        assertFalse("子命令不应稀释通用记忆查询", names.any { it in DILUTING_SUBCOMMANDS })
        // 精确查询不破坏: 删除/读取/梦境仍可精确命中子命令
        assertTrue(
            "删除记忆 应命中 agent.memory.rm",
            CommandSearch.search("删除记忆", 10).any { it.fullName == "agent.memory.rm" }
        )
        assertTrue(
            "读取记忆 应命中 agent.memory.read",
            CommandSearch.search("读取记忆", 10).any { it.fullName == "agent.memory.read" }
        )
        assertTrue(
            "梦境 仍可检索到",
            CommandSearch.search("梦境", 10).any { it.fullName == "agent.dream" }
        )
        // 英文通用查询同样收敛: 6 个记忆入口 + agent.docs (描述列举 Memory 文档目录, 合法命中)
        val en = CommandSearch.search("memory", 20)
        assertTrue("memory 查询应收敛, 实际 ${en.size}: ${en.map { it.fullName }}", en.size <= 8)
        assertTrue("英文查询应含核心入口", en.any { it.fullName == "agent.memory" })
    }

    private companion object {
        /** 三轨化后不应出现在通用"记忆"查询中的子命令。 */
        val DILUTING_SUBCOMMANDS = setOf(
            "agent.memory.write", "agent.memory.edit", "agent.memory.rm", "agent.memory.read",
            "agent.memory.record", "agent.memory.mid.delete", "agent.memory.mid.rm", "agent.memory.mid.edit",
            "agent.memory.project.save", "agent.memory.project.delete", "agent.memory.project.rm",
            "agent.memory.project.edit", "agent.dream"
        )
    }
}
