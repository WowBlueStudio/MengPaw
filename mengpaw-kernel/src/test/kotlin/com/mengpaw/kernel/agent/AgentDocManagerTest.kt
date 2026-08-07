// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P2-8(自检报告): agent.docs 展示文件头 frontmatter 元数据 (summary / read_when)。
 */
class AgentDocManagerTest {

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_agentdocmgr_test")
        File(DataPaths.BASE).deleteRecursively()
    }

    private fun workspace(agent: String): File {
        val dir = File(File(DataPaths.AGENTS, agent), "notes")  // 随意子目录, 只为建出父目录
        dir.mkdirs()
        return File(DataPaths.AGENTS, agent)
    }

    @Test
    fun `listDocs shows frontmatter summary and read_when`() {
        val ws = workspace("TestAgent")
        // 模板同款 frontmatter (缩进列表 + 双引号 summary)
        File(ws, "profile.md").writeText(
            """
            ---
            summary: "Agent 身份与用户资料"
            read_when:
              - 手动引导工作区
              - 每次对话必读
            ---

            ## 身份
            """.trimIndent()
        )
        File(ws, "soul.md").writeText(
            """
            ---
            summary: 灵魂准则
            ---

            _你不是聊天机器人。_
            """.trimIndent()
        )

        val docs = AgentDocManager(agentId = "TestAgent").listDocs()
        assertEquals("6 个 AgentDocType 文档", 6, docs.size)

        // summary + read_when (多条目 / 分隔)
        assertTrue("应含 summary 与 read_when", docs.any {
            it == "profile.md — Agent 身份与用户资料 [手动引导工作区 / 每次对话必读]"
        })
        // 只有 summary 无 read_when
        assertTrue("无 read_when 时省略括号", docs.any { it == "soul.md — 灵魂准则" })
    }

    @Test
    fun `docs without frontmatter fall back to plain name`() {
        val ws = workspace("TestAgent")
        File(ws, "soul.md").writeText("# 无 frontmatter 的灵魂")

        val docs = AgentDocManager(agentId = "TestAgent").listDocs()
        assertTrue("无 frontmatter 退化为文件名", docs.any { it == "soul.md" })
    }

    @Test
    fun `missing doc file falls back to plain name`() {
        val docs = AgentDocManager(agentId = "GhostAgent").listDocs()
        assertEquals(6, docs.size)
        assertTrue("文件不存在退化为文件名", docs.all { !it.contains("—") })
    }

    // ── CLI.md 陈旧检测: 命令集指纹 (v0.34.0) ──

    @Test
    fun `命令集变化后 cliDocStale 判定为陈旧`() {
        workspace("TestAgent")
        val pm = com.mengpaw.kernel.plugin.PluginManager()
        val mgr = AgentDocManager(agentId = "TestAgent")

        mgr.regenerateCliDoc(pm)
        assertFalse("生成后应立即新鲜", mgr.cliDocStale(pm))

        // 模拟内核升级新增命令 (如 agent.audit) — 活跃插件数不变
        mgr.registeredAgentCommands = listOf("audit")
        assertTrue("命令集变化必须触发重生成", mgr.cliDocStale(pm))

        // 重生成后恢复新鲜
        mgr.regenerateCliDoc(pm)
        assertFalse("重生成后恢复新鲜", mgr.cliDocStale(pm))
    }

    @Test
    fun `无指纹的旧 CLI 文件强制重生成一次`() {
        workspace("TestAgent")
        val f = File(File(DataPaths.AGENTS, "TestAgent"), "cli.md")
        // 旧版文件: 仅「活跃插件」行, 无命令指纹
        f.writeText("# MengPaw CLI 命令参考\n> 生成时间: 2026-01-01T00:00:00Z\n> 活跃插件: 0\n\n## 内置命令")
        val pm = com.mengpaw.kernel.plugin.PluginManager()
        assertTrue("旧文件无指纹必须强制重生成", AgentDocManager(agentId = "TestAgent").cliDocStale(pm))
    }
}
