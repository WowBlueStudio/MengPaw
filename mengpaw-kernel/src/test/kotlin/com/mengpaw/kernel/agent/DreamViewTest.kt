// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.memory.MemoryLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 归纳降级为视图 (P3, docs/memory-git-sync-design.md §6.3) —
 * 带 sources / 重跑版本化不覆盖 / 不删分片 / 未入账本标 unsourced。
 */
class DreamViewTest {

    private lateinit var base: File
    private val agent = "dream-agent"
    private val legacyAgent = "legacy-agent"
    private val date: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private val midRel = "memory/memory_$date.md"

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-dream-").toFile()
        DataPaths.initialize(base.absolutePath)
        File(DataPaths.AGENTS, agent).mkdirs()
        File(DataPaths.AGENTS, legacyAgent).mkdirs()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun writeMid(agentName: String, blocks: List<String>) {
        val file = File(File(DataPaths.AGENTS, agentName), midRel)
        file.parentFile?.mkdirs()
        file.writeText("# 中期记忆\n\n" + blocks.joinToString("\n\n") + "\n")
    }

    private fun appendLedger(block: String, wall: String): String {
        val result = MemoryLedger.appendNote(
            agentName = agent,
            track = MemoryLedger.TRACK_MID,
            file = midRel,
            block = block,
            device = "dev-a",
            session = "sess-1",
            wall = wall
        )
        return result.entryId
    }

    private fun dreamFile(version: Int? = null): File =
        File(DataPaths.AGENTS, "$agent/${date}_dream" + (version?.let { ".v$it" } ?: "") + ".md")

    @Test
    fun `归纳产物带 sources 且不删除分片`() {
        val first = "## 08:00:00\n\n第一条中期记忆"
        val second = "## 09:00:00\n\n第二条中期记忆"
        writeMid(agent, listOf(first, second))
        val firstId = appendLedger(first, "${date}T08:00:00+08:00")
        val secondId = appendLedger(second, "${date}T09:00:00+08:00")

        val result = DreamEngine.organize(agent)

        assertEquals(2, result.memoriesReviewed)
        val view = dreamFile()
        assertTrue("首版产物应存在", view.exists())
        val text = view.readText()
        assertTrue("必须带 sources 字段: $text", text.contains("sources: ["))
        assertTrue("来源条目 1: $text", text.contains(firstId))
        assertTrue("来源条目 2: $text", text.contains(secondId))
        assertTrue("分片必须保留", File(File(DataPaths.AGENTS, agent), midRel).exists())
        assertEquals("分片内容原样保留", 2, MemoryLedger.splitBlocks(File(File(DataPaths.AGENTS, agent), midRel).readText()).size)
        assertTrue("已归纳条目应从账本未归纳集合消失", MemoryLedger.undigested(agent).isEmpty())
        assertTrue(DreamEngine.hasTodayDream(agent))
    }

    @Test
    fun `重跑不覆盖旧产物并生成 v2`() {
        val block = "## 08:00:00\n\n可重跑条目"
        writeMid(agent, listOf(block))
        appendLedger(block, "${date}T08:00:00+08:00")

        DreamEngine.organize(agent)
        val v1Text = dreamFile().readText()
        assertFalse("v2 不应在首轮出现", dreamFile(2).exists())

        val second = DreamEngine.organize(agent)

        assertEquals("重跑用同一批条目重新生成", 1, second.memoriesReviewed)
        assertTrue("旧产物必须保留", dreamFile().exists())
        assertEquals("旧产物内容不变", v1Text, dreamFile().readText())
        assertTrue("重跑生成 .v2", dreamFile(2).exists())
        assertTrue("v2 同样带 sources", dreamFile(2).readText().contains("sources: ["))
    }

    @Test
    fun `未入账本的旧分片标 unsourced`() {
        val legacy = "## 07:00:00\n\n升级前的旧条目"
        writeMid(legacyAgent, listOf(legacy))

        val result = DreamEngine.organize(legacyAgent)

        assertEquals(1, result.memoriesReviewed)
        val view = File(DataPaths.AGENTS, "$legacyAgent/${date}_dream.md")
        assertTrue(view.exists())
        assertTrue("旧分片无 entryId → unsourced: ${view.readText()}", view.readText().contains("sources: [unsourced]"))
        assertTrue("旧分片同样不删除", File(File(DataPaths.AGENTS, legacyAgent), midRel).exists())
        assertTrue("无账本条目", MemoryLedger.entries(legacyAgent).isEmpty())
    }
}
