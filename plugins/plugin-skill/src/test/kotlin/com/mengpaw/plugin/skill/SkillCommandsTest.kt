// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.skill

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * skill 命令流集成测试 (P1 路径消毒在命令层的落实 + 参数替换)。
 * 每测试独立临时 DataPaths — 注意 SkillPlugin 构造时捕获 storageDir,
 * 必须先 DataPaths.initialize 再构造插件。
 */
class SkillCommandsTest {

    private lateinit var plugin: SkillPlugin
    private val ctx = ExecutionContext(sessionId = "skill-test", agentName = "tester")

    @Before
    fun setUp() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-skill-test-${System.currentTimeMillis()}")
        tmp.mkdirs()
        DataPaths.initialize(tmp.absolutePath)
        plugin = SkillPlugin()
    }

    private suspend fun run(cmd: String, vararg args: String) =
        plugin.commands[cmd]!!(args.toList(), ctx)

    // ── 名称合法性 (create 正则) ────────────────────────────────────────

    @Test
    fun `create rejects traversal and invalid names`() = runBlocking {
        assertFalse("穿越名应拒绝", run("create", "../evil").success)
        assertFalse("含空格应拒绝", run("create", "my skill").success)
        assertFalse("非 ASCII 应拒绝", run("create", "技能").success)
    }

    // ── 创建与落盘 ──────────────────────────────────────────────────────

    @Test
    fun `create writes skill to agent local dir`() = runBlocking {
        val r = run("create", "test-skill", "--category", "dev", "--description", "测试技能")
        assertTrue("合法创建应成功: ${r.error}", r.success)
        val file = File(DataPaths.agentSkillsDir("tester"), "test-skill.md")
        assertTrue("技能文件应写入 Agent 本地", file.exists())
        assertTrue(file.readText().contains("category: dev"))
    }

    @Test
    fun `unknown category falls back to general`() = runBlocking {
        val r = run("create", "cat-skill", "--category", "nonsense")
        assertTrue(r.success)
        val file = File(DataPaths.agentSkillsDir("tester"), "cat-skill.md")
        assertTrue(file.readText().contains("category: general"))
    }

    // ── 执行与参数替换 ──────────────────────────────────────────────────

    @Test
    fun `run substitutes key value params`() = runBlocking {
        run("create", "greet")
        val file = File(DataPaths.agentSkillsDir("tester"), "greet.md")
        file.writeText("---\nname: greet\ndescription: 问候\nenabled: true\ncategory: general\n---\n你好 {{who}}!")
        val r = run("run", "greet", "who=世界")
        assertTrue("执行应成功: ${r.error}", r.success)
        assertTrue("{{who}} 应被替换", r.output.contains("你好 世界!"))
    }

    @Test
    fun `run falls back to global pool when local missing`() = runBlocking {
        File(DataPaths.SKILLS).mkdirs()
        File(DataPaths.SKILLS, "global-skill.md").writeText(
            "---\nname: global-skill\ndescription: 全局\nenabled: true\ncategory: general\n---\n全局技能正文"
        )
        val r = run("run", "global-skill")
        assertTrue(r.success)
        assertTrue(r.output.contains("全局技能正文"))
    }

    // ── P1 消毒在命令层落实 ─────────────────────────────────────────────

    @Test
    fun `run rejects traversal name`() = runBlocking {
        val r = run("run", "../secret")
        assertFalse("穿越名不应命中任何技能", r.success)
        assertTrue((r.error ?: "").contains("not found", ignoreCase = true))
    }

    @Test
    fun `rm rejects traversal name as illegal`() = runBlocking {
        val r = run("rm", "../x")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("非法技能名"))
    }

    // ── 状态控制 ────────────────────────────────────────────────────────

    @Test
    fun `run disabled skill is denied`() = runBlocking {
        run("create", "off-skill")
        val f = File(DataPaths.agentSkillsDir("tester"), "off-skill.md")
        f.writeText(f.readText().replace("enabled: true", "enabled: false"))
        val r = run("run", "off-skill")
        assertFalse("停用技能应拒绝执行", r.success)
        assertTrue((r.error ?: "").contains("disabled", ignoreCase = true))
    }

    @Test
    fun `info shows skill details and params`() = runBlocking {
        run("create", "showcase")
        val f = File(DataPaths.agentSkillsDir("tester"), "showcase.md")
        f.writeText("---\nname: showcase\ndescription: 演示\nenabled: true\ncategory: meta\n---\n使用 {{param}} 参数")
        val r = run("info", "showcase")
        assertTrue(r.success)
        assertTrue(r.output.contains("演示"))
        assertTrue("占位符应列出", r.output.contains("{{param}}"))
    }
}
