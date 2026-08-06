// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.skill

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 技能路径消毒单测 (P1 修复: canonicalPath 前缀校验, 防 agent 经 skill.run/rm
 * 等 7 命令读删技能根目录外文件)。另覆盖 frontmatter 解析与模板生成纯逻辑。
 * 全部使用临时目录, 不触 DataPaths。
 */
class PathSanitizationTest {

    private lateinit var dir: File
    private val plugin = SkillPlugin()

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "skill-sanitize-${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ── skillFile 路径消毒 (P1) ─────────────────────────────────────────

    @Test
    fun `valid names resolve inside root`() {
        val f = plugin.skillFile(dir, "test-skill")
        assertNotNull("合法名称应放行", f)
        assertEquals(File(dir, "test-skill.md").canonicalPath, f!!.canonicalPath)
    }

    @Test
    fun `parent traversal names are rejected`() {
        assertNull(".. 越界应拒绝", plugin.skillFile(dir, "../escape"))
        assertNull("多级越界应拒绝", plugin.skillFile(dir, "a/../../escape"))
        assertNull("裸 .. 段应拒绝", plugin.skillFile(dir, "../.."))
    }

    @Test
    fun `absolute path names are rejected`() {
        val outside = File(System.getProperty("java.io.tmpdir"), "skill-outside-${System.currentTimeMillis()}")
        assertNull("绝对路径应拒绝", plugin.skillFile(dir, outside.absolutePath))
    }

    @Test
    fun `names staying inside root are allowed`() {
        assertNotNull("子目录内名称应放行", plugin.skillFile(dir, "sub/name"))
        assertNotNull("a/../b 归一化后仍在根内应放行", plugin.skillFile(dir, "a/../b"))
    }

    @Test
    fun `bare dot-dot is neutralized by md suffix`() {
        // ".." 拼上 ".md" 后缀成为普通文件名 "...md" (无路径分隔符, 不越界) — 固定此行为防回归
        val f = plugin.skillFile(dir, "..")
        assertNotNull(f)
        assertEquals(File(dir, "...md").canonicalPath, f!!.canonicalPath)
    }

    @Test
    fun `windows backslash traversal rejected on windows`() {
        if (File.separatorChar == '\\') {
            assertNull("反斜杠穿越应拒绝", plugin.skillFile(dir, "..\\..\\escape"))
        }
    }

    // ── parseSkill frontmatter ──────────────────────────────────────────

    @Test
    fun `parseSkill reads frontmatter fields`() {
        val f = File(dir, "hello.md").apply {
            writeText("---\nname: hello\ndescription: 打招呼\nenabled: false\ncategory: dev\n---\n# Hello\n正文内容")
        }
        val skill = plugin.parseSkill(f)
        assertNotNull(skill)
        assertEquals("hello", skill!!.name)
        assertEquals("打招呼", skill.description)
        assertFalse(skill.enabled)
        assertEquals("dev", skill.category)
        assertEquals("# Hello\n正文内容", skill.content)
    }

    @Test
    fun `parseSkill falls back to defaults without frontmatter`() {
        val f = File(dir, "plain.md").apply { writeText("# 无头部技能") }
        val skill = plugin.parseSkill(f)
        assertNotNull(skill)
        assertEquals("plain", skill!!.name)
        assertEquals("", skill.description)
        assertTrue(skill.enabled)
        assertEquals("general", skill.category)
    }

    @Test
    fun `parseSkill returns null for missing file`() {
        assertNull(plugin.parseSkill(File(dir, "ghost.md")))
    }

    // ── buildSkillTemplate ──────────────────────────────────────────────

    @Test
    fun `template contains frontmatter and category hints`() {
        val t = plugin.buildSkillTemplate("mytool", "dev", "开发工具")
        assertTrue(t.contains("name: mytool"))
        assertTrue(t.contains("description: 开发工具"))
        assertTrue(t.contains("enabled: true"))
        assertTrue(t.contains("category: dev"))
        assertTrue("dev 分类应有步骤模板", t.contains("## 执行步骤"))
    }

    @Test
    fun `office template mentions placeholders`() {
        val t = plugin.buildSkillTemplate("report", "office", "")
        assertTrue(t.contains("{{param}}"))
    }

    // ── categoryLabel ───────────────────────────────────────────────────

    @Test
    fun `categoryLabel maps known categories and passes through unknown`() {
        assertEquals(SkillPlugin.CATEGORIES["meta"], SkillPlugin.categoryLabel("meta"))
        assertEquals("unknown-cat", SkillPlugin.categoryLabel("unknown-cat"))
    }
}
