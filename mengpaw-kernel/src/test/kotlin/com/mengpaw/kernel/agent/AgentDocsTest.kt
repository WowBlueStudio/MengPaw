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

    @Test
    fun `countLongTermEntries excludes legacy template headings`() {
        // 旧模板形态: 全部 ## 标题为教学章节 → 计数 0
        writeWorkspace("TestAgent", "memory/memory.md",
            "## 这个文件是什么\n教学内容\n## 示例\n示例内容")
        assertEquals("旧模板教学章节不计入", 0, AgentDocs.countLongTermEntries("TestAgent"))
        // 追加真实记忆 (时间戳标题, 非黑名单) → 计数 1
        AgentDocs.appendLongTermMemory("TestAgent", "用户偏好: 开发文档特指 Dev Guide")
        assertEquals("真实记忆计入", 1, AgentDocs.countLongTermEntries("TestAgent"))
    }

    @Test
    fun `bootstrap migrates legacy memory template even when soul exists`() {
        // 老工作区: soul.md 存在 (bootstrap 会早退), memory.md 仍是原样旧模板
        writeTemplate("memory/memory.md", "zh", "## 新模板版记忆")
        writeWorkspace("TestAgent", "soul.md", "# 已有灵魂")
        writeWorkspace("TestAgent", "memory/memory.md",
            "## 这个文件是什么\n## 这里记什么\n## 示例\n## 怎么写入（用命令，别直接编辑文件）\n## 不记什么")
        AgentDocs.bootstrap("TestAgent")
        // 迁移在 soul.md 早退之前执行 → memory.md 被模板池新版覆盖
        assertEquals("旧模板应被迁移覆盖",
            "## 新模板版记忆",
            File(File(DataPaths.AGENTS, "TestAgent"), "memory/memory.md").readText())
    }

    @Test
    fun `docManager bindAgent resolves docs under bound directory`() {
        // FIX(自检报告 P0-2): 原硬编码 agent-001 读错目录, bindAgent 后命中真实工作区
        writeWorkspace("WorkAgent", "profile.md", "# 工作区档案")
        val mgr = AgentDocManager()
        assertEquals("绑定前读默认 agent-001 目录, 应为空", "", mgr.getDoc(AgentDocType.PROFILE))
        mgr.bindAgent("WorkAgent")
        assertEquals("绑定后应命中工作区档案", "# 工作区档案", mgr.getDoc(AgentDocType.PROFILE))
    }

    @Test
    fun `bootstrap does not migrate memory with real entries`() {
        writeTemplate("memory/memory.md", "zh", "## 新模板版记忆")
        writeWorkspace("TestAgent", "soul.md", "# 已有灵魂")
        // 混合: 一条真实时间戳标题 + 教学章节残留 → 不应迁移
        writeWorkspace("TestAgent", "memory/memory.md",
            "## 2026-08-05 14:30\n真实记忆内容\n## 示例\n示例内容")
        AgentDocs.bootstrap("TestAgent")
        val after = File(File(DataPaths.AGENTS, "TestAgent"), "memory/memory.md").readText()
        assertTrue("含真实记忆不迁移, 内容保留", after.contains("真实记忆内容"))
    }
}
