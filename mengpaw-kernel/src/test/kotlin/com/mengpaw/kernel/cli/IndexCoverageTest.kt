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
    fun `cli doc description tables cover every registered command`() {
        // CLI.md 描述表 (AGENT_COMMANDS/SELF_COMMANDS/PLUGIN_COMMANDS) 与注册键集
        // 双向一致 — 描述缺失时动态表降级提示 self.search, 但键集漂移必须 0
        val agentKeys = AgentExecutor(AgentDocManager()).commands.keys.toSet()
        val agentDesc = AgentDocManager.AGENT_COMMANDS.map { it.first }.toSet()
        assertTrue("CLI.md agent 描述表缺: ${agentKeys - agentDesc}", (agentKeys - agentDesc).isEmpty())
        assertTrue("CLI.md agent 描述表多: ${agentDesc - agentKeys}", (agentDesc - agentKeys).isEmpty())

        val selfKeys = SelfExecutor.commands.keys.toSet()
        val selfDesc = AgentDocManager.SELF_COMMANDS.map { it.first }.toSet()
        assertTrue("CLI.md self 描述表缺: ${selfKeys - selfDesc}", (selfKeys - selfDesc).isEmpty())
        assertTrue("CLI.md self 描述表多: ${selfDesc - selfKeys}", (selfDesc - selfKeys).isEmpty())

        val pluginKeys = PluginExecutor(PluginManager()).commands.keys.toSet()
        val pluginDesc = AgentDocManager.PLUGIN_COMMANDS.map { it.first }.toSet()
        assertTrue("CLI.md plugin 描述表缺: ${pluginKeys - pluginDesc}", (pluginKeys - pluginDesc).isEmpty())
        assertTrue("CLI.md plugin 描述表多: ${pluginDesc - pluginKeys}", (pluginDesc - pluginKeys).isEmpty())
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
}
