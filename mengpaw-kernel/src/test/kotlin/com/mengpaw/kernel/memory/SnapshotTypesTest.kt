// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 数据模型测试 — 同内容同 tree sha、空树、快照 ID 稳定、parents 不影响 tree。
 */
class SnapshotTypesTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-snapshot-types-").toFile()
        DataPaths.initialize(base.absolutePath)
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun `同内容同 tree sha`() {
        val files = mapOf("soul.md" to "a".repeat(64), "memory/memory.md" to "b".repeat(64))
        assertEquals(SnapshotStore.writeTree(files), SnapshotStore.writeTree(files))
    }

    @Test
    fun `空树可往返且 sha 稳定`() {
        val empty = SnapshotStore.writeTree(emptyMap())
        assertEquals(empty, SnapshotStore.writeTree(emptyMap()))
        assertEquals(emptyMap<String, String>(), SnapshotStore.readTree(empty))
        assertTrue("空树也应有对象", SnapshotStore.objectCount() >= 1)
    }

    @Test
    fun `快照 ID 稳定 - 重复读取同一 ID 不变`() {
        val blob = SnapshotStore.writeBlob("稳定".toByteArray())
        val first = SnapshotStore.commit("ws-stable", mapOf("soul.md" to blob), "说明", "agent", "dev")
        val a = SnapshotStore.snapshot("ws-stable", first)
        val b = SnapshotStore.snapshot("ws-stable", first)
        assertEquals("同对象重复读取 ID 必须一致", a?.id, b?.id)
        assertEquals(first, a?.id)
        assertEquals(first.take(Snapshot.SHORT_ID_CHARS), a?.shortId)
    }

    @Test
    fun `parents 影响快照但不影响 tree`() {
        val blob = SnapshotStore.writeBlob("tree独立".toByteArray())
        val files = mapOf("soul.md" to blob)
        val tree = SnapshotStore.writeTree(files)
        val first = SnapshotStore.commit("ws-parent", files, "一", "agent", "dev")
        val second = SnapshotStore.commit("ws-parent", files, "二", "agent", "dev")
        val s1 = SnapshotStore.snapshot("ws-parent", first)
        val s2 = SnapshotStore.snapshot("ws-parent", second)
        assertNotEquals("不同快照 ID 应不同", s1?.id, s2?.id)
        assertEquals("同文件集 tree 相同", tree, s1?.tree)
        assertEquals("同文件集 tree 相同", tree, s2?.tree)
        assertEquals(listOf(first), s2?.parents)
    }

    @Test
    fun `清单条目与映射互转`() {
        val blobs = listOf(ManifestBlob("b.md", "b".repeat(64)), ManifestBlob("a.md", "a".repeat(64)))
        val map = blobs.toFileMap()
        assertEquals("a".repeat(64), map["a.md"])
        assertEquals(listOf("a.md", "b.md"), map.toManifestBlobs().map { it.relPath })
    }
}
