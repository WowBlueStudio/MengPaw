// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.agent.AgentDocManager
import com.mengpaw.kernel.agent.AgentExecutor
import com.mengpaw.kernel.namespace.SelfExecutor
import com.mengpaw.kernel.plugin.PluginExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.PipelineManager
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 提示词幽灵引用检测 — 发现性铁律第三环 (v0.31.0):
 * 系统提示词 (PromptEngine.kt) 手写命令引用是最后一个无测试锁的 Agent 触达源 —
 * 重构删除/重命名内核命令时, 提示词里的引用不会自动更新 → Agent 按提示词调用
 * 必败命令 → 自检误报 (plugin.verify 同型)。本测试从源码提取 namespace.command
 * 形态引用, 与内核注册键集对照, 不在注册集的即幽灵。
 *
 * 边界: 动态/插件命名空间 (sys/framework/tavily/skill/twin/net/browser/search/root/
 * tribe/dev/fs/clipboard) 由插件或 Android 适配注册, 不在 kernel 测试注册表内, 跳过;
 * 代码符号 (normalized. 开头 / com.mengpaw / file.exists) 与教学元引用
 * (namespace.command) 及文件名伪匹配 (.md/.html) 显式排除。
 */
class PromptGhostReferenceTest {

    @Before
    fun reset() {
        CommandSearch.clear()
        SelfExecutor.commandRegistry = null
    }

    @Test
    fun `prompt command references resolve against kernel registry`() {
        val src = File("src/main/kotlin/com/mengpaw/kernel/llm/PromptEngine.kt")
        assumeTrue("PromptEngine.kt 应存在 (Gradle 测试工作目录 = 模块根)", src.exists())

        val pm = PluginManager()
        PipelineManager(pm, PluginExecutor(pm), AgentExecutor(AgentDocManager())).buildPipeline()
        val registered = SelfExecutor.commandRegistry!!.list().toSet()

        val text = src.readText()
        val ghosts = ghostsIn(text, registered)

        assertTrue(
            "提示词引用不存在的内核命令 (幽灵引导, Agent 调用必败): ${ghosts.sorted().joinToString()}",
            ghosts.isEmpty()
        )
    }

    @Test
    fun `prompt building sources avoid ghost command references`() {
        // v0.47.x 扩展: 原来只扫 PromptEngine.kt 一个文件。改为覆盖**构造 Agent 可读文本**的源文件 —
        // 与提示词通道不同, 这些文件里同时存在普通代码, 因此用显式范围而非全目录递归
        // (全目录递归会把 plugin.metadata.id 这类对象属性访问误判为命令引用)。
        val files = listOf(
            "src/main/kotlin/com/mengpaw/kernel/llm/PromptEngine.kt",
            "src/main/kotlin/com/mengpaw/kernel/llm/PromptSystemBuilder.kt",
            "src/main/kotlin/com/mengpaw/kernel/agent/PlanModeExecutor.kt",
            "src/main/kotlin/com/mengpaw/kernel/security/PromptFirewall.kt",
            "src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionProvider.kt",
            "src/main/kotlin/com/mengpaw/kernel/agent/AgentErrors.kt",
            "src/main/kotlin/com/mengpaw/kernel/AgentConversation.kt",
            "src/main/kotlin/com/mengpaw/kernel/cli/CommandResultCache.kt"
        ).map { File(it) }.filter { it.exists() }
        assumeTrue("至少应存在一个提示词构建源文件", files.isNotEmpty())

        val pm = PluginManager()
        PipelineManager(pm, PluginExecutor(pm), AgentExecutor(AgentDocManager())).buildPipeline()
        val registered = SelfExecutor.commandRegistry!!.list().toSet()

        val ghosts = mutableListOf<String>()
        files.forEach { file ->
            ghostsIn(file.readText(), registered).forEach { ghosts.add("${file.name}: $it") }
        }
        assertTrue(
            "提示词/文案构建源引用了不存在的内核命令 (幽灵引导):\n${ghosts.distinct().joinToString("\n")}",
            ghosts.isEmpty()
        )
    }

    @Test
    fun `retired namespaces never appear as command references`() {
        // 已退役命名空间: 在源码里出现即为幽灵命令引用 (注释中的历史说明由 RetiredReferenceScanTest 放行)
        val root = File("src/main/kotlin/com/mengpaw/kernel")
        assumeTrue("kernel 源码目录应存在", root.isDirectory)
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                // 说明其已退役的行放行 (历史说明, 不构成引路)
                if (ALLOW_MARKERS.any { line.contains(it) }) return@forEachIndexed
                RETIRED_NS.forEach { ns ->
                    if (Regex("${Regex.escape(ns)}(?![a-zA-Z])").containsMatchIn(line)) {
                        hits.add("${file.name}:${idx + 1}: ${line.trim().take(100)}")
                    }
                }
            }
        }
        assertTrue("内核源码出现已退役命名空间: ${hits.take(10)}", hits.isEmpty())
    }

    /** 提取文本中的幽灵内核命令引用 (命名空间必须在 [KERNEL_NS] 内)。
     *  含"已删/已移除/已退役"的行是历史说明, 先剔除再扫描。 */
    private fun ghostsIn(text: String, registered: Set<String>): List<String> {
        val scannable = text.lines()
            .filterNot { line -> ALLOW_MARKERS.any { line.contains(it) } }
            .joinToString("\n")
        val refs = Regex("(?<![A-Za-z0-9])[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*")
            .findAll(scannable).map { it.value }.toSet()
        return refs.filter { ref ->
            val ns = ref.substringBefore(".")
            if (ns !in KERNEL_NS) return@filter false
            if (ref in SELF_ACP_KEYS) return@filter false
            if (ref.endsWith(".md") || ref.endsWith(".html") || ref.endsWith(".txt") ||
                ref.endsWith(".json") || ref.endsWith(".kt") || ref.endsWith(".kts")
            ) return@filter false
            ref !in registered
        }.toList()
    }

}

/** 已退役命名空间 — 出现即幽灵 (fs 随 plugin-fs 退役, browser.mcp 随 9880 桥退役)。 */
private val RETIRED_NS = setOf("fs.", "browser.mcp")

/** 行内含这些词 → 视为说明其已退役的历史说明, 放行。 */
private val ALLOW_MARKERS = listOf("已退役", "已移除", "已删", "退役", "不再有", "空闲", "ghost-ok")

/** self.acp 子命令注册在 SelfAcpCommands (构建测试注册表未覆盖) — 真实存在, 非幽灵。 */
private val SELF_ACP_KEYS = setOf("self.acp.fingerprint", "self.acp.trusted")

/** 内核命名空间 — 注册表对照范围。动态/插件命名空间不在其中。 */
private val KERNEL_NS = setOf("self", "agent", "plugin", "evolution")
