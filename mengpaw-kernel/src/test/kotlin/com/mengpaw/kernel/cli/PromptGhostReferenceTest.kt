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
        val refs = Regex("(?<![A-Za-z0-9])[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*")
            .findAll(text).map { it.value }.toSet()

        val ghosts = refs.filter { ref ->
            val ns = ref.substringBefore(".")
            if (ns !in KERNEL_NS) return@filter false
            if (ref.endsWith(".md") || ref.endsWith(".html") || ref.endsWith(".txt")) return@filter false
            ref !in registered
        }

        assertTrue(
            "提示词引用不存在的内核命令 (幽灵引导, Agent 调用必败): ${ghosts.sorted().joinToString()}",
            ghosts.isEmpty()
        )
    }

}

/** 内核命名空间 — 注册表对照范围。动态/插件命名空间不在其中。 */
private val KERNEL_NS = setOf("self", "agent", "plugin", "evolution")
