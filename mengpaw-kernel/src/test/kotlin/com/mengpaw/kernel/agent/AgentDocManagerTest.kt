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
        assertEquals("5 个 AgentDocType 文档 (v0.34.3 CLI 移除)", 5, docs.size)

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
        assertEquals(5, docs.size)
        assertTrue("文件不存在退化为文件名", docs.all { !it.contains("—") })
    }

    // ── 工作区进化档案目录可见性 (v0.34.3) ──

    @Test
    fun `listDocs includes evolution dir when archive exists`() {
        val ws = workspace("EvoAgent")
        val evo = File(ws, "evolution")
        evo.mkdirs()
        File(evo, "failures.jsonl").writeText("{}")
        File(evo, "reactions.md").writeText("# 用户反应")

        val docs = AgentDocManager(agentId = "EvoAgent").listDocs()
        assertTrue("有档案时 agent.docs 应显示 evolution/ 目录",
            docs.any { it.startsWith("evolution/ — 进化档案") && it.contains("failures.jsonl") })
        assertEquals("5 文档 + 1 进化档案", 6, docs.size)
    }

    @Test
    fun `listDocs omits evolution dir when archive empty`() {
        workspace("NoEvoAgent")

        val docs = AgentDocManager(agentId = "NoEvoAgent").listDocs()
        assertTrue("无档案时不显示 evolution/", docs.none { it.startsWith("evolution/") })
        assertEquals("保持 5 个文档", 5, docs.size)
    }

    // v0.34.3: CLI.md 工作区文档删除 — 陈旧检测/指纹测试随 CliDocGenerator 一并移除
}
