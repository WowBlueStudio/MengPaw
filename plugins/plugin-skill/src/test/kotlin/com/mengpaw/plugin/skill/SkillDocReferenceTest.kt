// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.skill

import com.mengpaw.kernel.cli.BuiltinCommandIndex
import com.mengpaw.kernel.cli.CommandSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 技能文档命令引用守护 (v0.47.x) — 补上"分发给 Agent 的运行时资产"这一幽灵引用盲区。
 *
 * 背景: IndexCoverageTest 只覆盖内核注册表 ↔ 索引, PromptGhostReferenceTest 只扫
 * PromptEngine.kt 四个命名空间 — 技能文档 (assets/skills 下的 md, 每次启动同步进全局池)
 * **没有任何守护**: 命令被删/插件退役后, 手册照旧教 Agent 调用, 形成"必败手册"
 * (实测: filesystem.md 整篇教已删的 fs.* 十条命令, 而系统提示词正指向它)。
 *
 * 本测试断言: 技能文档不得引用已删命令 / 已退役命名空间, 且 `skill.run <name>` 目标必须存在。
 */
class SkillDocReferenceTest {

    private val skillsDir: File = File("src/main/assets/skills")

    /** 已删命令 (v0.36.x 命令去重)。 */
    private val RETIRED_COMMANDS = setOf(
        "agent.read", "agent.write", "agent.ls", "agent.rm", "agent.mkdir"
    )

    /** 已退役命名空间 (命令不存在, 只剩空闲命名)。 */
    private val RETIRED_NAMESPACES = setOf("fs.", "browser.mcp")

    /** 已整体移除的文档 — 不得作为"命令参考"来源出现。 */
    private val RETIRED_FILES = listOf("CLI.md", "cli.md")

    /** 允许作为「示例/历史」出现、不要求真实注册的命令 — 每条必须写明原因。 */
    private val ALLOWED_DOC_REFS = emptySet<String>()

    /** 允许提及退役对象的行/文件 — 必须是**说明其已删**的语境, 不是教 Agent 用它。 */
    private val ALLOW_MARKERS = listOf(
        "已删", "已移除", "已退役", "退役", "不再存在", "不再有", "已拆", "空闲", "ghost-ok"
    )

    private fun skillFiles(): List<File> =
        skillsDir.listFiles { f -> f.extension == "md" }?.sortedBy { it.name } ?: emptyList()

    private fun hits(pattern: (String) -> Boolean): List<String> {
        val found = mutableListOf<String>()
        skillFiles().forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                if (ALLOW_MARKERS.any { line.contains(it) }) return@forEachIndexed
                // root 通道的 root.fs.* 是仍有效的命令 (与已删的 plugin-fs 的 fs.* 无关)
                if (line.contains("root.fs")) return@forEachIndexed
                if (pattern(line)) found.add("${file.name}:${idx + 1}: ${line.trim().take(120)}")
            }
        }
        return found
    }

    @Test
    fun `技能文档不得引用已删命令`() {
        val found = hits { line -> RETIRED_COMMANDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(line) } }
        assertTrue(
            "技能文档引用了已删命令 (Agent 照做必败):\n${found.joinToString("\n")}",
            found.isEmpty()
        )
    }

    @Test
    fun `技能文档不得引用已退役命名空间`() {
        val found = hits { line -> RETIRED_NAMESPACES.any { line.contains(it) } }
        assertTrue(
            "技能文档引用了已退役命名空间:\n${found.joinToString("\n")}",
            found.isEmpty()
        )
    }

    @Test
    fun `技能文档不得把 CLI md 当作命令参考`() {
        val found = hits { line -> RETIRED_FILES.any { line.contains(it) } }
        assertTrue(
            "技能文档引用了已整体移除的 CLI 手册:\n${found.joinToString("\n")}",
            found.isEmpty()
        )
    }

    @Test
    fun `技能索引引用的 skill 必须存在`() {
        assertTrue("技能目录应存在: ${skillsDir.absolutePath}", skillsDir.isDirectory)
        val existing = skillFiles().map { it.nameWithoutExtension }.toSet()
        val referenced = mutableListOf<String>()
        skillFiles().forEach { file ->
            Regex("skill\\.run\\s+([A-Za-z0-9_-]+)").findAll(file.readText()).forEach { m ->
                // 排除末尾点号/逗号粘连
                val name = m.groupValues[1].trim('.', ',', ')', '`')
                if (name.isNotEmpty()) referenced.add(name)
            }
        }
        val missing = referenced.distinct().filter { it !in existing }
        assertTrue(
            "技能文档引用了不存在的技能 (skill.run 必败): ${missing.sorted()}",
            missing.isEmpty()
        )
    }

    @Test
    fun `技能文档命令引用形态健康`() {
        // 反向保护: 文档里不该再出现 fs./agent.read 这类已删形态的书写残留
        val suspicious = hits { line ->
            Regex("\\bfs\\.[a-z]+").containsMatchIn(line) && !line.contains("root-fs", ignoreCase = true)
        }
        assertTrue("疑似已删 fs.* 引用: ${suspicious.joinToString("\n")}", suspicious.isEmpty())
    }

    // ── 命令引用存在性 (防下一次腐化) ──────────────────────────────

    /**
     * twin-guide 是 Agent 获取孪生/记忆历史/设备库存能力的唯一手册, 命令改名或插件退役后
     * 它不会自动更新 —— 本用例把手册里的引用逐条对回**真实注册源**:
     * `twin.*` 对照 `MemoryTwinPlugin.metadata.commands`, `memory.*` / `device.*`
     * (含 `agent.memory.*`) 对照 `BuiltinCommandIndex`。
     *
     * 不直接依赖 plugin-memory-twin 模块: 插件之间不得互相依赖 (项目红线), 因此按源码
     * 解析它的命令注册块; 解析为空 = 失败 (防止"解析不到 → 全放过"的假绿)。
     */
    @Test
    fun `twin-guide 引用的命令必须真实存在`() {
        val guide = File(skillsDir, "twin-guide.md")
        assertTrue("孪生手册应存在: ${guide.absolutePath}", guide.isFile)

        val refs = commandRefsIn(guide.readText())
        assertTrue("twin-guide 应引用 twin/memory/device 命令 (否则覆盖不到新能力)", refs.isNotEmpty())
        assertTrue("twin-guide 应至少引用一条 twin.* 命令", refs.any { it.startsWith("twin.") })

        val ghosts = ghostsOf(refs, builtinCommands(), twinCommands())
        assertTrue(
            "twin-guide 引用了不存在/未注册的命令 (Agent 照做必败):\n${ghosts.joinToString("\n")}",
            ghosts.isEmpty()
        )
    }

    /** 反向用例: 夹具里注入幽灵命令 → 校验必须检出 (守护自身不可失效)。 */
    @Test
    fun `幽灵命令注入必须被守护发现`() {
        val fixture = """
            # 反例夹具 — 以下命令都不存在
            - `memory.nonexistent` (幽灵)
            - `device.nonexistent` (幽灵)
            - `twin.nonexistent` (幽灵)
        """.trimIndent()
        val ghosts = ghostsOf(commandRefsIn(fixture), builtinCommands(), twinCommands())
        assertEquals(
            "夹具中的幽灵命令必须全部检出: $ghosts",
            listOf("device.nonexistent", "memory.nonexistent", "twin.nonexistent"),
            ghosts
        )
    }

    /** 文档里的命令引用: `twin.*` / `memory.*` / `device.*` / `agent.memory.*`。
     *  前瞻排除 `/`：`memory/memory.md` 这类文件路径不是命令。 */
    private fun commandRefsIn(text: String): Set<String> =
        Regex(
            "(?<![A-Za-z0-9_./-])((?:agent\\.)?(?:memory|device)\\.[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*" +
                "|twin\\.[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*)"
        ).findAll(text).map { it.groupValues[1] }.toSet()

    /** 引用 → 幽灵列表; 白名单条目视为「示例/历史」放行。 */
    private fun ghostsOf(refs: Set<String>, builtin: Set<String>, twin: Set<String>): List<String> =
        refs.filter { ref ->
            if (ref in ALLOWED_DOC_REFS) return@filter false
            if (ref.startsWith("twin.")) ref !in twin else ref !in builtin
        }.sorted()

    /** 内核命令索引 (BuiltinCommandIndex → CommandSearch)。用完清空, 不污染其他用例。 */
    private fun builtinCommands(): Set<String> {
        CommandSearch.clear()
        try {
            BuiltinCommandIndex.buildAll()
            return CommandSearch.all().map { it.fullName }.toSet()
        } finally {
            CommandSearch.clear()
        }
    }

    /** `MemoryTwinPlugin.metadata.commands` 的命令集 — 按源码注册块解析 (插件间不相互依赖)。 */
    private fun twinCommands(): Set<String> {
        val source = File(
            "../plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/MemoryTwinPlugin.kt"
        )
        assertTrue("孪生命令注册源应存在: ${source.absolutePath}", source.isFile)
        val block = source.readText().substringAfter("commands = listOf(", "")
        val names = Regex("\"(twin\\.[a-z0-9.]+)\"").findAll(block)
            .map { it.groupValues[1] }.toSet()
        assertTrue("应解析出孪生命令注册块 (实际解析: $names)", names.size >= 15)
        return names
    }
}
