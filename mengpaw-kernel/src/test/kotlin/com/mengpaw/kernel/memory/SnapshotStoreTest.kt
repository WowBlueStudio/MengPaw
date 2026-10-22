// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
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
 * 对象库测试 — blob 去重/幂等、损坏防御、tree 往返、parents、GC 可达性、原子写。
 */
class SnapshotStoreTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-snapshot-store-").toFile()
        DataPaths.initialize(base.absolutePath)
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun blobPath(id: String): File =
        File(File(File(DataPaths.TWIN_STORE, "objects"), id.take(2)), id.drop(2))

    @Test
    fun `blob 同内容去重且幂等`() {
        val first = SnapshotStore.writeBlob("重复内容".toByteArray())
        val objectsAfterFirst = SnapshotStore.objectCount()
        val second = SnapshotStore.writeBlob("重复内容".toByteArray())
        assertEquals("同内容应返回同 sha", first, second)
        assertEquals("重复写入不得新增对象", objectsAfterFirst, SnapshotStore.objectCount())
        assertTrue("读取内容应一致", String(SnapshotStore.readBlob(first) ?: ByteArray(0)) == "重复内容")
    }

    @Test
    fun `损坏对象当缺失处理`() {
        val id = SnapshotStore.writeBlob("会被破坏的内容".toByteArray())
        blobPath(id).writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNull("损坏对象应返回 null", SnapshotStore.readBlob(id))
        assertNull("不存在对象应返回 null", SnapshotStore.readBlob("f".repeat(64)))
    }

    @Test
    fun `tree 往返与键序无关`() {
        val files = linkedMapOf("memory/memory.md" to "a".repeat(64), "soul.md" to "b".repeat(64))
        val id = SnapshotStore.writeTree(files)
        assertEquals(files, SnapshotStore.readTree(id))

        val reversed = linkedMapOf("soul.md" to "b".repeat(64), "memory/memory.md" to "a".repeat(64))
        assertEquals("键序不同但内容相同 → 同 tree sha", id, SnapshotStore.writeTree(reversed))
    }

    @Test
    fun `snapshot parents 与 heads 前进`() {
        val ns = "ws-test"
        val blob = SnapshotStore.writeBlob("v1".toByteArray())
        val tree = SnapshotStore.writeTree(mapOf("soul.md" to blob))
        val first = SnapshotStore.commit(ns, mapOf("soul.md" to blob), "第一版", "agent", "dev-A")
        assertEquals(listOf(first), SnapshotStore.heads(ns))
        assertEquals(first, SnapshotStore.localRef(ns))

        val second = SnapshotStore.commit(ns, mapOf("soul.md" to blob), "第二版", "agent", "dev-A")
        val snap = SnapshotStore.snapshot(ns, second)
        assertNotNull(snap)
        assertEquals("第二个快照应以第一个为父", listOf(first), snap?.parents)
        assertEquals("heads 应前进到新快照", listOf(second), SnapshotStore.heads(ns))
        assertEquals(tree, snap?.tree)
        assertEquals(2, SnapshotStore.history(ns, 20).size)
        assertEquals("limit 应生效", 1, SnapshotStore.history(ns, 1).size)
    }

    @Test
    fun `GC 清理不可达对象但保留可达对象`() {
        val ns = "ws-gc"
        val blob = SnapshotStore.writeBlob("被引用的内容".toByteArray())
        SnapshotStore.commit(ns, mapOf("soul.md" to blob), "快照", "agent", "dev")
        val orphan = SnapshotStore.writeBlob("孤儿对象".toByteArray())
        assertNotNull(SnapshotStore.readBlob(orphan))

        val removed = SnapshotStore.gc(ns, keepDays = 0, keepCount = 0)
        assertTrue("应清理至少一个不可达对象", removed >= 1)
        assertNull("孤儿对象应被清理", SnapshotStore.readBlob(orphan))
        assertNotNull("可达 blob 必须保留", SnapshotStore.readBlob(blob))
        assertTrue("快照本身必须保留", SnapshotStore.snapshot(ns, SnapshotStore.heads(ns).first()) != null)
    }

    @Test
    fun `GC 保留策略取宽 - 未达龄的孤儿快照保留`() {
        val active = "ws-active"
        val other = "ws-other"
        val blob = SnapshotStore.writeBlob("活跃".toByteArray())
        SnapshotStore.commit(active, mapOf("soul.md" to blob), "活跃快照", "agent", "dev")
        // 另一个命名空间的快照对象对本命名空间不可达, 但刚创建 (未达龄) → 宽策略应保留
        SnapshotStore.commit(other, mapOf("soul.md" to blob), "别处快照", "agent", "dev")
        val before = SnapshotStore.objectCount()

        SnapshotStore.gc(active, keepDays = 30, keepCount = 0)
        assertEquals("未达龄对象应被宽策略保留", before, SnapshotStore.objectCount())

        SnapshotStore.gc(active, keepDays = 0, keepCount = 0)
        assertTrue("零保留窗口下应清理不可达对象", SnapshotStore.objectCount() < before)
    }

    @Test
    fun `原子写后 tmp 目录不残留`() {
        val blob = SnapshotStore.writeBlob("atomic".toByteArray())
        SnapshotStore.commit("ws-tmp", mapOf("soul.md" to blob), "快照", "Agent", "dev")
        val tmp = File(DataPaths.TWIN_STORE, "tmp")
        val leftovers = tmp.listFiles()?.toList() ?: emptyList()
        assertTrue("tmp 不应残留: ${leftovers.map { it.name }}", leftovers.isEmpty())
        assertFalse("refs 应可读", SnapshotStore.heads("ws-tmp").isEmpty())
        assertTrue("库体积应大于 0", SnapshotStore.storeSizeBytes() > 0)
    }
}
