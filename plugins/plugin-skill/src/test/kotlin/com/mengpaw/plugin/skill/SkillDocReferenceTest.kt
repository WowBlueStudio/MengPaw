// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.skill

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
}
