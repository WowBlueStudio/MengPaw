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
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 已删命令 / 已退役命名空间 引用扫描 (v0.47.x) — 补上"提示词文件之外的 Agent 可读渠道"盲区。
 *
 * 背景: PromptGhostReferenceTest 只扫 `PromptEngine.kt` 一个文件, 于是 PlanModeExecutor 的
 * 计划提示词、PromptFirewall 的策略文本、EvolutionProvider 的错误示例里的已删命令长期存活
 * (实测 A 类 49 处)。本测试对**全部生产源码 + 资产**做静态扫描。
 *
 * 判定原则 (只报"会到达 Agent 的引用", 不报注释里的历史说明):
 *  - 已删命令 (`agent.read` 等): 只在**字符串字面量或文档资产**中出现才算命中
 *    (注释里的"已随命令去重移除"是合法历史说明);
 *  - 已退役命名空间 (`fs.*`): 加对象方法白名单 (`fs.mkdirs` 是 java.io.File 调用, 不是命令);
 *  - 已移除手册 (`CLI.md`): 只在字符串字面量 / 文档资产中命中
 *    (代码注释与"孪生排除规则"保留无害)。
 */
class RetiredReferenceScanTest {

    /** 已删命令 (v0.36.x 命令去重)。 */
    private val RETIRED_COMMANDS = listOf(
        "agent.read", "agent.write", "agent.ls", "agent.rm", "agent.mkdir"
    )

    /** 已退役命名空间的命令前缀。 */
    private val RETIRED_COMMAND_PREFIXES = listOf("fs.cat", "fs.ls", "fs.write", "fs.rm", "fs.mkdir", "fs.cp", "fs.mv", "fs.stat", "fs.grep", "fs.glob", "browser.mcp")

    /** `fs.` 后面这些 token 是 FS/File 对象方法调用, 不是命令。 */
    private val FS_OBJECT_METHODS = setOf(
        "mkdirs", "mkdir", "listFiles", "listFiltered", "exists", "isDirectory", "isFile",
        "readText", "writeText", "appendText", "delete", "deleteRecursively", "absolutePath",
        "canonicalPath", "name", "parentFile", "walkTopDown", "walk", "length", "copyTo", "renameTo"
    )

    private val RETIRED_FILES = listOf("CLI.md", "cli.md")

    /** 行内含这些词 → 视为"说明其已删/已移除"的历史说明, 放行。 */
    private val ALLOW_MARKERS = listOf(
        "已删", "已移除", "已退役", "退役", "不再存在", "不再有", "空闲", "ghost-ok",
        "retired", "removed", "no longer", "not exist"
    )

    private fun repoRoot(): File =
        listOf(File(".."), File(".")).firstOrNull { File(it, "settings.gradle.kts").exists() } ?: File("..")

    private fun productionSources(): List<File> {
        val root = repoRoot()
        val dirs = listOf("mengpaw-kernel/src/main", "mengpaw-core/src/main", "mengpaw-shell/src/main", "harness/src/main")
            .map { File(root, it) }.filter { it.isDirectory }
        val pluginDirs = File(root, "plugins").listFiles { f -> f.isDirectory }
            ?.map { File(it, "src/main") }?.filter { it.isDirectory } ?: emptyList()
        return (dirs + pluginDirs).flatMap { dir ->
            dir.walkTopDown().filter { it.isFile }
                .filter { it.extension in setOf("kt", "kts", "md", "json") }
                .toList()
        }
    }

    /** 去掉注释部分 — 注释里的历史说明不算命中。 */
    private fun stripComment(line: String): String {
        val idx = listOf(line.indexOf("//"), line.indexOf("*")).filter { it >= 0 }.minOrNull() ?: return line
        return line.substring(0, idx)
    }

    private fun scan(predicate: (String, File) -> Boolean): List<String> {
        val hits = mutableListOf<String>()
        productionSources().forEach { file ->
            val isDoc = file.extension == "md" || file.extension == "json"
            try {
                file.readLines().forEachIndexed { idx, raw ->
                    if (ALLOW_MARKERS.any { raw.contains(it, ignoreCase = true) }) return@forEachIndexed
                    val text = if (isDoc) raw else stripComment(raw)
                    if (text.isBlank()) return@forEachIndexed
                    if (predicate(text, file)) {
                        val rel = file.absolutePath.substringAfter("MengPaw").removePrefix(File.separator)
                        hits.add("$rel:${idx + 1}: ${raw.trim().take(120)}")
                    }
                }
            } catch (_: Exception) {
                // 不可读文件跳过 (不扩大失败面)
            }
        }
        return hits
    }

    @Before
    fun reset() {
        CommandSearch.clear()
        SelfExecutor.commandRegistry = null
    }

    @Test
    fun `生产源码不得在 Agent 可读文本中引用已删命令`() {
        val hits = scan { text, _ ->
            RETIRED_COMMANDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
        }
        assertTrue(
            "生产源码在 Agent 可读文本中引用了已删命令 (会造成必败调用):\n" +
                hits.take(30).joinToString("\n") + if (hits.size > 30) "\n... 共 ${hits.size} 处" else "",
            hits.isEmpty()
        )
    }

    @Test
    fun `生产源码不得引用已退役命名空间命令`() {
        val hits = scan { text, file ->
            // root 插件注册的是 root.fs.* (合法的 root 通道命令, 键名短形 "fs.ls" 经命名空间推导为 root.fs.ls)
            val isRootPlugin = file.absolutePath.contains("plugin-root")
            RETIRED_COMMAND_PREFIXES.any { prefix ->
                // 命令边界: 前缀后必须是非字母字符 (防 fs.mkdir 误配 fs.mkdirs 对象方法)
                if (!Regex("${Regex.escape(prefix)}(?![a-zA-Z])").containsMatchIn(text)) return@any false
                if (prefix.startsWith("fs.")) {
                    if (isRootPlugin || text.contains("root.fs")) return@any false
                    val suffix = text.substringAfter(prefix).removePrefix(".").takeWhile { it.isLetter() }
                    // 对象方法调用 (fs.mkdirs) → 不是命令引用
                    suffix !in FS_OBJECT_METHODS
                } else true
            }
        }
        assertTrue("生产源码引用了已退役命名空间命令:\n" + hits.take(30).joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `生产源码不得在 Agent 可读文本中引用已移除的 CLI 手册`() {
        val hits = scan { text, file ->
            // 保留用途不算命中: 只读文档名单 / 孪生同步排除规则 (都是"别动这个文件", 非"去读命令参考")
            val isRetentionUse = text.contains("READONLY_DOCS") || text.contains("EXCLUDED_FILES")
            val mentions = RETIRED_FILES.any { text.contains(it) }
            if (isRetentionUse || !mentions) return@scan false
            // 文档资产: 出现即命中; 源码: 仅带引号的字面量 (会进入 Agent 可读文本)
            file.extension == "md" || file.extension == "json" ||
                RETIRED_FILES.any { text.contains("\"$it\"") }
        }
        assertTrue("仍把已移除的 CLI 手册当作命令参考:\n" + hits.take(30).joinToString("\n"), hits.isEmpty())
    }

    // ── 安全判定表在册性: 表内命令必须真实存在 ─────────────────────

    @Test
    fun `高危与分级表内命令必须在册`() {
        BuiltinCommandIndex.buildAll()
        val pm = PluginManager()
        PipelineManager(pm, PluginExecutor(pm), AgentExecutor(AgentDocManager())).buildPipeline()
        val registered = SelfExecutor.commandRegistry?.list()?.toSet() ?: emptySet()
        val indexed = CommandSearch.all().map { it.fullName }.toSet()

        // sys.* (Android 适配层动态注册) + 插件命令 + proc.* (宿主进程能力, Android 侧插件提供)
        // 不在本测试注册表内 — 按命名空间豁免。
        val dynamicNamespaces = setOf(
            "sys", "net", "tavily", "skill", "framework", "twin", "tribe", "root",
            "clipboard", "office", "tools", "update", "search", "render", "translate",
            "concise", "proc", "security", "swarm", "fleet", "evolution", "plugin", "agent", "self"
        )
        fun known(cmd: String) = cmd in registered || cmd in indexed || cmd.substringBefore(".") in dynamicNamespaces

        val missing = (com.mengpaw.kernel.security.CommandRiskLevels.LEVELS.keys +
            com.mengpaw.kernel.security.HighRiskCommandGate.HIGH_RISK.keys)
            .filter { !known(it) }
        assertTrue(
            "风险分级/高危表登记了不存在的命令 (判定恒不命中): ${missing.sorted()}",
            missing.isEmpty()
        )
    }
}
