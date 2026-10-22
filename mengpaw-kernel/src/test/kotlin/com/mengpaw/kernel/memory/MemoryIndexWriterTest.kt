// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.llm.PromptEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 记忆索引测试 (P3, docs/memory-git-sync-design.md §6.4) —
 * 项目分组/计数/最近 3 条标题; 二次写入内容变化; 提示词注入随 mtime 失效。
 */
class MemoryIndexWriterTest {

    private lateinit var base: File
    private val agent = "index-agent"
    private val projectFile = "memory/project_alpha_memory.md"
    private val midFile = "memory/memory_2026-10-22.md"

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-index-").toFile()
        DataPaths.initialize(base.absolutePath)
        File(DataPaths.AGENTS, agent).mkdirs()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun projectBlock(hour: String, body: String): String =
        "## 2026-10-22 $hour · 里程碑总结\n\n$body\n---"

    private fun writeBlocks(relPath: String, header: String, blocks: List<String>) {
        val file = File(File(DataPaths.AGENTS, agent), relPath)
        file.parentFile?.mkdirs()
        file.writeText(header + "\n\n" + blocks.joinToString("\n\n") + "\n")
    }

    private fun addEntry(relPath: String, block: String, project: String, wall: String, track: String = MemoryLedger.TRACK_PROJECT) {
        val result = MemoryLedger.appendNote(
            agentName = agent,
            track = track,
            file = relPath,
            block = block,
            project = project,
            device = "dev-a",
            wall = wall
        )
        assertNull("账本写入失败: ${result.error}", result.error)
    }

    private fun seedFourMilestones() {
        val blocks = listOf(
            projectBlock("10:00", "一"),
            projectBlock("11:00", "二"),
            projectBlock("12:00", "三"),
            projectBlock("13:00", "四")
        )
        writeBlocks(projectFile, "# 项目记忆: alpha", blocks)
        blocks.forEachIndexed { index, block ->
            addEntry(projectFile, block, "alpha", "2026-10-22T${10 + index}:00:00+08:00")
        }
    }

    @Test
    fun `项目分组计数与最近三条标题`() {
        seedFourMilestones()
        val mid = "## 08:00:00\n\n未归属中期条目"
        writeBlocks(midFile, "# 中期", listOf(mid))
        addEntry(midFile, mid, "", "2026-10-22T08:00:00+08:00", MemoryLedger.TRACK_MID)

        val index = MemoryIndexWriter.rebuild(agent)
        assertNotNull(index)
        val text = index.orEmpty()

        assertTrue("首行必须声明自动生成: $text", text.lineSequence().first().contains("自动生成"))
        assertTrue("应有项目分组: $text", text.contains("## 项目: alpha"))
        assertTrue("应有未归类分组: $text", text.contains("未归类"))
        assertTrue("项目计数: $text", text.contains("- 条目: 4 | 未归纳: 4"))
        assertTrue("最近一条标题: $text", text.contains("2026-10-22 13:00 · 里程碑总结"))
        assertTrue(text.contains("2026-10-22 12:00 · 里程碑总结"))
        assertTrue(text.contains("2026-10-22 11:00 · 里程碑总结"))
        assertFalse("只列最近 3 条, 最旧的不得出现", text.contains("2026-10-22 10:00 · 里程碑总结"))
    }

    @Test
    fun `第二次写入后索引内容变化`() {
        seedFourMilestones()
        val first = MemoryIndexWriter.rebuild(agent).orEmpty()
        assertTrue(first.contains("条目: 4"))

        val fifth = projectBlock("14:00", "五")
        val file = File(File(DataPaths.AGENTS, agent), projectFile)
        file.writeText(file.readText() + "\n" + fifth + "\n")
        addEntry(projectFile, fifth, "alpha", "2026-10-22T14:00:00+08:00")

        val second = MemoryIndexWriter.rebuild(agent).orEmpty()
        assertTrue("计数应更新: $second", second.contains("条目: 5"))
        assertTrue("最新标题应出现: $second", second.contains("2026-10-22 14:00 · 里程碑总结"))
        assertFalse("内容必须变化 (缓存指纹才会失效)", first == second)
    }

    @Test
    fun `提示词注入索引摘要并随索引 mtime 失效`() {
        seedFourMilestones()
        MemoryIndexWriter.rebuild(agent)
        val engine = PromptEngine()
        val before = engine.buildSystemPrompt(agentName = agent)
        assertTrue("应注入记忆索引段: $before", before.contains("你的记忆索引"))
        assertTrue(before.contains("项目: alpha"))

        Thread.sleep(10) // 保证 mtime 变化
        val fifth = projectBlock("14:00", "五")
        val file = File(File(DataPaths.AGENTS, agent), projectFile)
        file.writeText(file.readText() + "\n" + fifth + "\n")
        addEntry(projectFile, fifth, "alpha", "2026-10-22T14:00:00+08:00")
        MemoryIndexWriter.rebuild(agent)

        val after = engine.buildSystemPrompt(agentName = agent)
        assertTrue("索引变化后提示词必须重建: $after", after.contains("条目: 5"))
        assertTrue("常驻冲突裁决指引", after.contains("冲突标记"))
    }
}
