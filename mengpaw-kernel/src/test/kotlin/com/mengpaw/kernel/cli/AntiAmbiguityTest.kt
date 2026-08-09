// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 命令反歧义审查回归锁 (v0.34.3 全量审计后固化) — 后续新增/重构命令时:
 *
 * 1. 路径拼接型命令 (joinToString 全拼路径/时间戳) 必须接入 ParamGuard.pollutedHint
 * 2. 单 token 位置参数命令必须接入 ParamGuard.extraArgsHint (防"静默忽略")
 * 3. 系统提示词 / CLI.md 必须保留参数纯净规则
 *
 * 新增命令若走"拼接型"或"单标识符"形态而未接防护, 应补防护并在本测试登记。
 */
class AntiAmbiguityTest {

    /** 定位源码 — 兼容 Gradle 测试工作目录为项目根或模块根两种环境。 */
    private fun src(moduleRelative: String): String {
        val candidates = listOf(
            File("mengpaw-kernel/src/main/kotlin/$moduleRelative"), // 工作目录 = 项目根
            File("src/main/kotlin/$moduleRelative")                 // 工作目录 = 模块根
        )
        val f = candidates.firstOrNull { it.exists() }
        assumeTrue("源码文件应存在: $moduleRelative (候选: $candidates)", f != null)
        return f!!.readText()
    }

    /** 定位插件源码 — 兼容双工作目录。 */
    private fun pluginSrc(pluginPath: String): String {
        val candidates = listOf(
            File("plugins/$pluginPath"),               // 工作目录 = 项目根
            File("../../plugins/$pluginPath")          // 工作目录 = 模块根
        )
        val f = candidates.firstOrNull { it.exists() }
        assumeTrue("插件源码应存在: $pluginPath", f != null)
        return f!!.readText()
    }

    @Test
    fun `路径拼接型命令必须接入污染提示`() {
        val text = src("com/mengpaw/kernel/agent/AgentFileCommands.kt")
        listOf(
            "pollutedHint(args, \"agent.read\")",
            "pollutedHint(args, \"agent.ls\")",
            "pollutedHint(pathArgs, \"agent.rm\")",
            "pollutedHint(args, \"agent.mkdir\")"
        ).forEach { guard ->
            assertTrue("AgentFileCommands 必须保留 $guard (路径污染防护): $text", text.contains(guard))
        }
    }

    @Test
    fun `memory 时间戳拼接命令必须接入污染提示`() {
        val text = src("com/mengpaw/kernel/agent/AgentMemoryMutateCommands.kt")
        assertTrue("mid.rm 时间戳拼接须有污染防护", text.contains("pollutedHint(args.drop(1), \"agent.memory.mid.rm\")"))
        assertTrue("project.rm 时间戳拼接须有污染防护", text.contains("pollutedHint(args.drop(1), \"agent.memory.project.rm\")"))
    }

    @Test
    fun `单 token 位置参数命令必须接入多余参数提示`() {
        // 插件在项目根 plugins/ 下 — 工作目录 = kernel 模块根, 上跳一级
        val fs = pluginSrc("plugin-fs/src/main/kotlin/com/mengpaw/plugin/fs/FsPlugin.kt")
        assertTrue("fs.cp 须有多余参数提示", fs.contains("extraArgsHint(args, 2, \"fs.cp\")"))
        assertTrue("fs.mv 须有多余参数提示", fs.contains("extraArgsHint(args, 2, \"fs.mv\")"))
        assertTrue("fs.stat 须有多余参数提示", fs.contains("extraArgsHint(args, 1, \"fs.stat\")"))
        val net = pluginSrc("plugin-net/src/main/kotlin/com/mengpaw/plugin/net/NetPlugin.kt")
        assertTrue("net.curl 须有多余参数提示", net.contains("extraArgsHint(args, 1, \"net.curl\")"))
    }

    @Test
    fun `提示词与 CLI 文档保留参数纯净规则`() {
        val prompt = src("com/mengpaw/kernel/llm/PromptEngine.kt")
        assertTrue("系统提示词须含路径参数纯净规则", prompt.contains("路径参数纯净"))
        val cli = src("com/mengpaw/kernel/agent/CliDocGenerator.kt")
        assertTrue("CLI.md 须含参数纯净规则", cli.contains("参数纯净规则"))
    }

    @Test
    fun `ParamGuard 描述词表非空且覆盖实证词`() {
        assertTrue("描述词表应含用户实证词", ParamGuard.DESCRIPTION_WORDS.contains("等待结果"))
        assertTrue("描述词表应非空", ParamGuard.DESCRIPTION_WORDS.isNotEmpty())
    }

    @Test
    fun `关键命令描述与实现语义锁定`() {
        // v0.34.3 教训: BuiltinCommandIndex v0.16.0 手写 idx 时 agent.audit/plugin.auto
        // 描述与实现不符, 潜伏 9 版后经 P2-8 合并暴露进 CLI.md。锁语义防再犯。
        val text = src("com/mengpaw/kernel/cli/BuiltinCommandIndex.kt")
        fun idxLine(name: String): String =
            text.lines().firstOrNull { it.contains("idx(\"$name\"") }
                ?: error("BuiltinCommandIndex 缺 $name 条目")

        val audit = idxLine("agent.audit")
        assertTrue("agent.audit 描述应含'审计' (实现是审计日志)", audit.contains("审计"))
        assertFalse("agent.audit 描述不得再写'安全检查' (实现是审计日志)", audit.contains("安全检查"))

        val auto = idxLine("plugin.auto")
        assertTrue("plugin.auto 描述应含'省电' (实现是 wake/sleep/sleep-idle)", auto.contains("省电"))
        assertFalse("plugin.auto 描述不得再写'自动更新' (实现是省电)", auto.contains("自动更新"))

        val cleanup = idxLine("agent.cleanup")
        assertTrue("agent.cleanup 描述应含'截图'", cleanup.contains("截图"))
        assertTrue("agent.cleanup 描述应含'收件箱'", cleanup.contains("收件箱"))

        val output = idxLine("agent.output")
        assertTrue("agent.output 描述应标'只读'", output.contains("只读"))
    }
}
