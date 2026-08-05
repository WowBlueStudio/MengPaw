// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AgentDocsTest {

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_agentdocs_test")
        // 清理上次运行残留
        File(DataPaths.BASE).deleteRecursively()
    }

    private fun writeTemplate(relativePath: String, lang: String, content: String) {
        val f = File(DataPaths.AGENT_TEMPLATES, "$lang/$relativePath")
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    private fun writeWorkspace(agent: String, relativePath: String, content: String) {
        val f = File(File(DataPaths.AGENTS, agent), relativePath)
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    @Test
    fun `bootstrap creates Notes directory`() {
        AgentDocs.bootstrap("TestAgent")
        assertTrue("bootstrap 应预建 Notes 目录",
            File(File(DataPaths.AGENTS, "TestAgent"), "Notes").isDirectory)
    }

    @Test
    fun `resetDoc overwrites workspace doc with template`() {
        writeTemplate("agents.md", "zh", "# 模板版 agents")
        writeWorkspace("TestAgent", "agents.md", "# 已被 Agent 修改的内容")
        assertTrue("重置应成功", AgentDocs.resetDoc("TestAgent", "agents.md"))
        assertEquals("工作区内容应为模板原文",
            "# 模板版 agents", File(File(DataPaths.AGENTS, "TestAgent"), "agents.md").readText())
    }

    @Test
    fun `resetDoc resets memory subfile`() {
        writeTemplate("memory/memory.md", "zh", "# 模板记忆")
        writeWorkspace("TestAgent", "memory/memory.md", "# 修改过的记忆")
        assertTrue(AgentDocs.resetDoc("TestAgent", "memory/memory.md"))
        assertEquals("# 模板记忆", File(File(DataPaths.AGENTS, "TestAgent"), "memory/memory.md").readText())
    }

    @Test
    fun `resetDoc falls back to zh when requested lang template missing`() {
        writeTemplate("soul.md", "zh", "# 中文灵魂模板")
        writeWorkspace("TestAgent", "soul.md", "# 修改过的灵魂")
        assertTrue("en 模板缺失应回退 zh 成功", AgentDocs.resetDoc("TestAgent", "soul.md", language = "en"))
        assertEquals("# 中文灵魂模板", File(File(DataPaths.AGENTS, "TestAgent"), "soul.md").readText())
    }

    @Test
    fun `resetDoc returns false and keeps file when template missing`() {
        writeWorkspace("TestAgent", "soul.md", "# 不可丢失的内容")
        assertFalse("模板缺失应返回 false", AgentDocs.resetDoc("TestAgent", "soul.md"))
        assertEquals("原文件不被破坏",
            "# 不可丢失的内容", File(File(DataPaths.AGENTS, "TestAgent"), "soul.md").readText())
    }

    @Test
    fun `resetDoc rejects path traversal`() {
        writeWorkspace("TestAgent", "soul.md", "# 不可丢失的内容")
        assertFalse(".. 路径应被拒绝", AgentDocs.resetDoc("TestAgent", "../evil.md"))
        assertFalse("绝对路径应被拒绝", AgentDocs.resetDoc("TestAgent", "/etc/passwd"))
        assertEquals("原文件不被破坏",
            "# 不可丢失的内容", File(File(DataPaths.AGENTS, "TestAgent"), "soul.md").readText())
    }
}
