// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三方合并策略表测试 (P2, docs/memory-git-sync-design.md §5.2) —
 * L 类 diff3 / frontmatter 键级 / M 类并集 / 删除-修改 / 大文件兜底 / CRLF 归一 / rules 并集 / 分类。
 */
class TreeMergeTest {

    private val devA = MergeSide("dev-a", 3)
    private val devB = MergeSide("dev-b", 2)

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)
    private fun text(bytes: ByteArray?) = bytes?.toString(Charsets.UTF_8)

    private fun merge(
        path: String,
        base: String?,
        ours: String?,
        theirs: String?,
        oursSide: MergeSide = devA,
        theirsSide: MergeSide = devB,
        cls: TwinFileClass = TreeMerge.classify(path)
    ): FileMerge = TreeMerge.mergeFile(
        path, base?.let(::bytes), ours?.let(::bytes), theirs?.let(::bytes), oursSide, theirsSide, cls
    )

    // ── L 类 ───────────────────────────────────────────────────────

    @Test
    fun `L 类两侧改不同行无冲突`() {
        val merged = merge(
            "soul.md",
            base = "line1\nline2\nline3\n",
            ours = "line1\nline2 ours\nline3\n",
            theirs = "line1\nline2\nline3\ntheirs append\n"
        )
        assertEquals("line1\nline2 ours\nline3\ntheirs append\n", text(merged.content))
        assertNull("不同行改动不得产生冲突", merged.conflict)
        assertFalse(text(merged.content).orEmpty().contains("<<<<<<<"))
    }

    @Test
    fun `L 类两侧改同一行产生带设备名的冲突块`() {
        val merged = merge(
            "soul.md",
            base = "a\nb\nc\n",
            ours = "a\nB-ours\nc\n",
            theirs = "a\nB-theirs\nc\n"
        )
        val body = text(merged.content).orEmpty()
        assertTrue(body.contains("<<<<<<< ours (dev-a)"))
        assertTrue(body.contains("======="))
        assertTrue(body.contains(">>>>>>> theirs (dev-b)"))
        assertTrue(body.contains("B-ours"))
        assertTrue(body.contains("B-theirs"))
        assertNotNull(merged.conflict)
        assertEquals(1, merged.conflict?.hunks)
        assertTrue("冲突计数应覆盖双方行", (merged.conflict?.lines ?: 0) >= 2)
    }

    // ── frontmatter 键级 ───────────────────────────────────────────

    @Test
    fun `frontmatter 不同键取并集且不产生行级冲突`() {
        val base = "---\ntitle: 记忆\na: 1\nb: 1\n---\n正文\n"
        val ours = "---\ntitle: 记忆\na: 2\nb: 1\n---\n正文\n"
        val theirs = "---\ntitle: 记忆\na: 1\nb: 2\n---\n正文\n"
        val merged = merge("memory/memory.md", base, ours, theirs)
        val body = text(merged.content).orEmpty()
        assertTrue("a 应取本机的新值: $body", body.contains("a: 2"))
        assertTrue("b 应取对端的新值: $body", body.contains("b: 2"))
        assertFalse("frontmatter 不应产生行级冲突", body.contains("<<<<<<<"))
        assertEquals(0, merged.conflict?.hunks ?: 0)
    }

    @Test
    fun `frontmatter 同键双改按较新一方取值并标注来源`() {
        val base = "---\na: 0\n---\nbody\n"
        val ours = "---\na: 1\n---\nbody\n"
        val theirs = "---\na: 2\n---\nbody\n"
        // theirsSide lamport 更大 → 对端胜出, 并在块尾标注
        val merged = merge("soul.md", base, ours, theirs,
            oursSide = MergeSide("dev-a", 1), theirsSide = MergeSide("dev-b", 9))
        val body = text(merged.content).orEmpty()
        assertTrue("同键应取较新一侧: $body", body.contains("a: 2"))
        assertTrue("需标注取值来源: $body", body.contains("合并取值: a ← dev-b"))
    }

    // ── M 类追加并集 ───────────────────────────────────────────────

    @Test
    fun `M 类追加并集且顺序稳定`() {
        val base = "条目0\n"
        val ours = "条目0\n本机条目\n"
        val theirs = "条目0\n对端条目\n"
        val first = merge("memory/memory_2026-10-22.md", base, ours, theirs,
            oursSide = MergeSide("dev-a", 1), theirsSide = MergeSide("dev-b", 2))
        assertEquals("条目0\n本机条目\n对端条目\n", text(first.content))

        // 交换输入侧 (排序键跟着交换) → 结果必须一致, 不依赖参数顺序
        val second = merge("memory/memory_2026-10-22.md", base, theirs, ours,
            oursSide = MergeSide("dev-b", 2), theirsSide = MergeSide("dev-a", 1))
        assertEquals(text(first.content), text(second.content))
    }

    @Test
    fun `M 类两侧追加相同块不重复`() {
        val merged = merge("memory/memory_2026-10-22.md", "base\n", "base\nsame\n", "base\nsame\n")
        assertEquals("base\nsame\n", text(merged.content))
    }

    // ── 删除 vs 修改 ───────────────────────────────────────────────

    @Test
    fun `删除 vs 修改保留修改方为 kept 文件并要 inbox`() {
        val merged = merge("soul.md", base = "old\n", ours = null, theirs = "modified\n",
            oursSide = MergeSide("dev-a", 5), theirsSide = MergeSide("dev-b", 1))
        assertNull("删除应生效于原路径", merged.content)
        assertEquals(1, merged.extras.size)
        assertEquals("soul.kept-dev-b.md", merged.extras[0].relPath)
        assertEquals("modified\n", text(merged.extras[0].bytes))
        assertNotNull("需产生冲突记录", merged.conflict)
        assertNotNull("需写 inbox 提示", merged.inboxNote)
    }

    @Test
    fun `本机修改对端删除同样保留修改方`() {
        val merged = merge("memory/memory.md", base = "old\n", ours = "mine\n", theirs = null,
            oursSide = MergeSide("dev-a", 5), theirsSide = MergeSide("dev-b", 1))
        assertNull(merged.content)
        assertEquals("memory/memory.kept-dev-a.md", merged.extras[0].relPath)
        assertEquals("mine\n", text(merged.extras[0].bytes))
    }

    // ── 兜底 ───────────────────────────────────────────────────────

    @Test
    fun `超过 256KB 的改动取较新一方并留 conflict 备份`() {
        val big = "x".repeat(TreeMerge.MAX_MERGE_BYTES + 1)
        val merged = merge("soul.md", base = "old\n", ours = big, theirs = "theirs\n",
            oursSide = MergeSide("dev-a", 9), theirsSide = MergeSide("dev-b", 1))
        assertEquals("较新一方内容胜出", big, text(merged.content))
        assertEquals(1, merged.extras.size)
        assertTrue("备份名必须含 .conflict.", merged.extras[0].relPath.contains(".conflict."))
        assertEquals("theirs\n", text(merged.extras[0].bytes))
        assertNotNull(merged.conflict)
    }

    @Test
    fun `非 UTF-8 字节走兜底不参与行级合并`() {
        val invalid = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x41)
        val merged = TreeMerge.mergeFile(
            "soul.md", bytes("old\n"), invalid, bytes("regular\n"),
            MergeSide("dev-a", 1), MergeSide("dev-b", 9), TwinFileClass.L
        )
        assertEquals("对端为较新 (lamport 9) → 取对端", "regular\n", text(merged.content))
        assertEquals(1, merged.extras.size)
        assertTrue(merged.extras[0].bytes.contentEquals(invalid))
    }

    // ── CRLF ───────────────────────────────────────────────────────

    @Test
    fun `CRLF 归一后合并输出不含回车`() {
        val merged = merge("soul.md", base = "a\nb\n", ours = "a\r\nb\r\n", theirs = "a\nb\nc\n")
        val body = text(merged.content).orEmpty()
        assertFalse("输出必须归一为 LF: ${body.replace("\r", "<CR>")}", body.contains("\r"))
        assertTrue(body.contains("c"))
    }

    // ── rules json ─────────────────────────────────────────────────

    @Test
    fun `rules json 按 id 并集且同 id 取较新`() {
        val base = """{"version":1,"rules":[{"id":"r1","match":"a","note":"base"}]}"""
        val ours = """{"version":1,"rules":[{"id":"r1","match":"a","note":"ours"},{"id":"r2","match":"b"}]}"""
        val theirs = """{"version":2,"rules":[{"id":"r1","match":"a","note":"theirs"},{"id":"r3","match":"c"}]}"""
        val merged = merge("twin-model-rules.json", base, ours, theirs,
            oursSide = MergeSide("dev-a", 1), theirsSide = MergeSide("dev-b", 9))
        val body = text(merged.content).orEmpty()
        assertTrue("r1/r2/r3 都应在: $body", body.contains("\"r1\"") && body.contains("\"r2\"") && body.contains("\"r3\""))
        assertTrue("同 id 取较新 (对端): $body", body.contains("theirs"))
        assertFalse("rules 合并不产生行级冲突", body.contains("<<<<<<<"))
        assertTrue("顶层字段取较新一侧", body.contains("\"version\":2"))
    }

    // ── D 类 ───────────────────────────────────────────────────────

    @Test
    fun `D 类同 deviceId 双写拒绝合并并告警`() {
        val merged = merge("devices/dev-a.md", base = "v1\n", ours = "v2\n", theirs = "v3\n",
            oursSide = MergeSide("dev-a", 5), theirsSide = MergeSide("dev-a", 6), cls = TwinFileClass.D)
        assertNotNull("双写必须告警", merged.warning)
        assertTrue(merged.warning.orEmpty().contains("双写"))
        assertNull("拒绝合并 → 不产生冲突备份", merged.conflict)
    }

    // ── 分类 ───────────────────────────────────────────────────────

    @Test
    fun `文件分类覆盖 L M D rules S`() {
        assertEquals(TwinFileClass.L, TreeMerge.classify("soul.md"))
        assertEquals(TwinFileClass.L, TreeMerge.classify("memory/memory.md"))
        assertEquals(TwinFileClass.M, TreeMerge.classify("memory/memory_2026-10-22.md"))
        assertEquals(TwinFileClass.M, TreeMerge.classify("memory/project_mengpaw_memory.md"))
        assertEquals(TwinFileClass.M, TreeMerge.classify("2026-10-22_dream.md"))
        assertEquals(TwinFileClass.D, TreeMerge.classify("devices/dev-a.md"))
        assertEquals(TwinFileClass.RULES, TreeMerge.classify("twin-model-rules.json"))
        assertEquals(TwinFileClass.S, TreeMerge.classify("inbox/task.md"))
        assertEquals(TwinFileClass.S, TreeMerge.classify("dialog/chat.md"))
        assertEquals(TwinFileClass.S, TreeMerge.classify("memory/backup/x.md"))
    }

    // ── 整集合并 ───────────────────────────────────────────────────

    @Test
    fun `整集合并产出写入与删除清单`() {
        val base = mapOf("soul.md" to bytes("a\n"), "old.md" to bytes("gone\n"))
        val ours = mapOf("soul.md" to bytes("a ours\n"), "old.md" to bytes("gone\n"))
        val theirs = mapOf("soul.md" to bytes("a theirs\n"))
        val outcome = TreeMerge.merge(base, ours, theirs, MergeSide("dev-a", 2), MergeSide("dev-b", 1))
        assertEquals(listOf("old.md"), outcome.deletes)
        assertEquals("soul.md", outcome.writes.first().relPath)
        assertTrue(outcome.writes.first().bytes.toString(Charsets.UTF_8).contains("a ours"))
    }
}
