// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
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
import java.io.RandomAccessFile

/**
 * 记忆账本测试 (P3, docs/memory-git-sync-design.md §6.1) —
 * 追加/查询/坏行/并集去重/顺序稳定/三重上限。
 */
class MemoryLedgerTest {

    private lateinit var base: File
    private val agent = "ledger-agent"

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-ledger-").toFile()
        DataPaths.initialize(base.absolutePath)
        ErrorCollector.clear()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun entry(
        body: String,
        file: String = "memory/memory_2026-10-22.md",
        track: String = MemoryLedger.TRACK_MID,
        project: String = "",
        device: String = "dev-a",
        wall: String = "2026-10-22T08:00:00+08:00",
        agentName: String = agent
    ): MemoryLedger.Entry = MemoryLedger.newEntry(
        agentName = agentName,
        track = track,
        file = file,
        block = body,
        project = project,
        device = device,
        session = "sess-1",
        wall = wall
    )

    // ── 追加 / 查询 ───────────────────────────────────────────────

    @Test
    fun `追加后可按文件项目未归纳查询`() {
        val first = entry("## 08:00:00\n\n第一条", wall = "2026-10-22T08:00:00+08:00")
        val second = entry("## 09:00:00\n\n第二条", wall = "2026-10-22T09:00:00+08:00")
        val project = entry(
            "## 2026-10-22 10:00 · 里程碑总结\n\n项目结论\n---",
            file = "memory/project_alpha_memory.md",
            track = MemoryLedger.TRACK_PROJECT,
            project = "alpha",
            wall = "2026-10-22T10:00:00+08:00"
        )
        listOf(first, second, project).forEach {
            val result = MemoryLedger.append(it)
            assertTrue("追加失败: ${result.error}", result.error == null)
        }

        assertEquals(2, MemoryLedger.byFile(agent, "memory/memory_2026-10-22.md").size)
        assertEquals("项目过滤", 1, MemoryLedger.byProject(agent, "alpha").size)
        assertEquals("未归纳应含三条 (digest 尚未写入)", 3, MemoryLedger.undigested(agent).size)
        assertEquals("日期区间过滤", 2, MemoryLedger.query(agent, fromDate = "2026-10-22", toDate = "2026-10-22", file = "memory/memory_2026-10-22.md").size)

        val digest = MemoryLedger.appendDigest(agent, "2026-10-22_dream.md", listOf(first.entryId), "视图")
        assertNull(digest.error)
        assertEquals("已归纳一条 → 未归纳剩两条", 2, MemoryLedger.undigested(agent).size)
        assertTrue(MemoryLedger.digestedIds(agent).contains(first.entryId))
        assertFalse("digest 条目本身不算未归纳", MemoryLedger.undigested(agent).any { it.kind == MemoryLedger.KIND_DIGEST })
    }

    @Test
    fun `重复 entryId 幂等跳过不重复写行`() {
        val item = entry("## 08:00:00\n\n同一条")
        assertTrue(MemoryLedger.append(item).appended)
        val again = MemoryLedger.append(item)
        assertFalse("重复条目不追加", again.appended)
        assertNull("重复不是错误", again.error)
        assertEquals(1, MemoryLedger.entries(agent).size)
        assertEquals(1, MemoryLedger.file(agent).readLines().count { it.isNotBlank() })
    }

    // ── 双设备同日并集 (验收项) ─────────────────────────────────────

    @Test
    fun `双设备同日追加并集后两条都在且顺序稳定`() {
        val deviceA = entry("## 22:40:00\n\n平板写的", device = "dev-a", wall = "2026-10-22T22:40:00+08:00")
        val deviceB = entry("## 22:41:00\n\n台式机写的", device = "dev-b", wall = "2026-10-22T22:41:00+08:00")

        val ab = MemoryLedger.unionMerge(listOf(deviceA), listOf(deviceB))
        val ba = MemoryLedger.unionMerge(listOf(deviceB), listOf(deviceA))

        assertEquals("两条都必须保留", 2, ab.size)
        assertTrue(ab.any { it.device == "dev-a" } && ab.any { it.device == "dev-b" })
        assertEquals("合并方向不影响顺序", ab.map { it.entryId }, ba.map { it.entryId })
        assertEquals("按 wall 升序稳定", listOf(deviceA.entryId, deviceB.entryId), ab.map { it.entryId })
    }

    @Test
    fun `unionMerge 按 entryId 去重`() {
        val a = entry("## 08:00:00\n\nA", wall = "2026-10-22T08:00:00+08:00")
        val b = entry("## 09:00:00\n\nB", wall = "2026-10-22T09:00:00+08:00")
        val union = MemoryLedger.unionMerge(listOf(a, b), listOf(b))
        assertEquals(2, union.size)
        assertEquals(1, union.count { it.entryId == b.entryId })
    }

    // ── 坏行 ───────────────────────────────────────────────────────

    @Test
    fun `坏行跳过并记 ErrorCollector`() {
        val good = entry("## 08:00:00\n\n好条目")
        MemoryLedger.append(good)
        MemoryLedger.file(agent).appendText("{这不是 JSON\n\n{\"entryId\":\"\"}\n", Charsets.UTF_8)

        val result = MemoryLedger.read(agent)

        assertEquals("合法行仍可读", 1, result.entries.size)
        assertEquals("两条坏行被跳过", 2, result.badLines)
        assertTrue(
            "坏行须进 ErrorCollector",
            ErrorCollector.list(20).any { it.source.contains("MemoryLedger") }
        )
    }

    // ── 三重上限 ───────────────────────────────────────────────────

    @Test
    fun `单行超限拒绝且不写文件`() {
        // 账本只存 blockHash, 正文长度不影响单行 — 用超长 project 元数据把 JSONL 行撑过上限
        val result = MemoryLedger.appendNote(
            agentName = agent,
            track = MemoryLedger.TRACK_MID,
            file = "memory/memory_2026-10-22.md",
            block = "## 08:00:00\n\n正文很短",
            project = "p".repeat(MemoryLedger.MAX_LINE_BYTES + 100)
        )
        assertFalse(result.appended)
        assertNotNull("必须报错而不是静默截断", result.error)
        assertTrue(result.error.orEmpty().contains("单条超限"))
        assertFalse("拒绝后不得落盘", MemoryLedger.file(agent).exists())
    }

    @Test
    fun `条目数超限拒绝追加`() {
        val target = MemoryLedger.file(agent)
        target.parentFile?.mkdirs()
        val lines = buildString {
            repeat(MemoryLedger.MAX_ENTRIES) { index -> appendLine("{\"entryId\":\"sha256:fake$index\"}") }
        }
        target.writeText(lines, Charsets.UTF_8)

        val result = MemoryLedger.appendNote(
            agentName = agent,
            track = MemoryLedger.TRACK_MID,
            file = "memory/memory_2026-10-22.md",
            block = "## 08:00:00\n\n超出条数"
        )
        assertFalse(result.appended)
        assertTrue(result.error.orEmpty().contains("条数超限"))
    }

    @Test
    fun `文件体积超限拒绝追加`() {
        val target = MemoryLedger.file(agent)
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { it.setLength(MemoryLedger.MAX_FILE_BYTES + 1) }

        val result = MemoryLedger.appendNote(
            agentName = agent,
            track = MemoryLedger.TRACK_MID,
            file = "memory/memory_2026-10-22.md",
            block = "## 08:00:00\n\n超出体积"
        )
        assertFalse(result.appended)
        assertTrue(result.error.orEmpty().contains("文件超限"))
    }
}
