// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

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

/**
 * 实测证据库测试 (2026-09-10) — "用中学"的落地保证:
 * 证据必须能落盘、能跨进程读回、对损坏数据免疫, 且 per provider 隔离。
 */
class ModelEvidenceStoreTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = File(System.getProperty("java.io.tmpdir"), "mengpaw-evidence-${System.nanoTime()}")
        DataPaths.initialize(base.absolutePath)
        ModelEvidenceStore.clearAll()
        ModelEvidenceStore.invalidateCache()
    }

    @After
    fun tearDown() {
        ModelEvidenceStore.invalidateCache()
        DataPaths.initialize("/sdcard/MengPaw")
        base.deleteRecursively()
    }

    @Test
    fun `记录证据落盘且可跨缓存读回`() {
        ModelEvidenceStore.record("deepseek-flash", "deepseek", EvidenceFact.VISION_OK)
        ModelEvidenceStore.record("deepseek-flash", "deepseek", EvidenceFact.SUCCESS)

        // 丢弃内存缓存 → 强制从磁盘重读 (模拟重启后的进程)
        ModelEvidenceStore.invalidateCache()
        val e = ModelEvidenceStore.evidenceFor("deepseek-flash", "deepseek")
        assertNotNull(e)
        assertEquals(1, e?.visionOk)
        assertEquals(1, e?.successCount)
        assertEquals(2, e?.factCount)
        assertTrue("证据文件应真实落盘", ModelEvidenceStore.filePath().exists())
    }

    @Test
    fun `上下文成功取最大值_溢出取最小上界`() {
        ModelEvidenceStore.record("m1", "p1", EvidenceFact.CONTEXT_OK, 100_000)
        ModelEvidenceStore.record("m1", "p1", EvidenceFact.CONTEXT_OK, 250_000)
        ModelEvidenceStore.record("m1", "p1", EvidenceFact.CONTEXT_OK, 180_000)
        assertEquals(250_000, ModelEvidenceStore.evidenceFor("m1", "p1")?.maxObservedContext)

        ModelEvidenceStore.record("m2", "p1", EvidenceFact.CONTEXT_OVERFLOW, 500_000)
        ModelEvidenceStore.record("m2", "p1", EvidenceFact.CONTEXT_OVERFLOW, 300_000)
        assertEquals("溢出规模取最保守 (最小) 的一次", 300_000, ModelEvidenceStore.evidenceFor("m2", "p1")?.ctxOverflowAt)
    }

    @Test
    fun `provider 维度隔离_同名模型不同网关互不污染`() {
        ModelEvidenceStore.record("deepseek-flash", "gateway-a", EvidenceFact.VISION_REJECTED)
        ModelEvidenceStore.record("deepseek-flash", "gateway-b", EvidenceFact.VISION_OK)

        assertEquals(1, ModelEvidenceStore.evidenceFor("deepseek-flash", "gateway-a")?.visionRejected)
        assertEquals(0, ModelEvidenceStore.evidenceFor("deepseek-flash", "gateway-a")?.visionOk)
        assertEquals(1, ModelEvidenceStore.evidenceFor("deepseek-flash", "gateway-b")?.visionOk)
    }

    @Test
    fun `清除单条与全部`() {
        ModelEvidenceStore.record("m1", "p1", EvidenceFact.SUCCESS)
        ModelEvidenceStore.record("m2", "p1", EvidenceFact.FAILURE)

        assertTrue(ModelEvidenceStore.clear("m1", "p1"))
        assertNull(ModelEvidenceStore.evidenceFor("m1", "p1"))
        assertNotNull(ModelEvidenceStore.evidenceFor("m2", "p1"))
        assertFalse("重复清除返回 false", ModelEvidenceStore.clear("m1", "p1"))

        assertEquals(1, ModelEvidenceStore.clearAll())
        assertTrue(ModelEvidenceStore.snapshot().isEmpty())
    }

    @Test
    fun `损坏的证据文件视为空库且不抛异常`() {
        val file = ModelEvidenceStore.filePath()
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 JSON ][")
        ModelEvidenceStore.invalidateCache()

        assertNull(ModelEvidenceStore.evidenceFor("m1", "p1"))
        // 坏文件不应妨碍继续记录 (覆盖写回)
        ModelEvidenceStore.record("m1", "p1", EvidenceFact.SUCCESS)
        ModelEvidenceStore.invalidateCache()
        assertEquals(1, ModelEvidenceStore.evidenceFor("m1", "p1")?.successCount)
    }

    @Test
    fun `快照按更新时间倒序且含矛盾标记`() {
        ModelEvidenceStore.record("m-old", "p1", EvidenceFact.SUCCESS)
        Thread.sleep(5)
        ModelEvidenceStore.record("m-new", "p1", EvidenceFact.VISION_REJECTED)

        val snap = ModelEvidenceStore.snapshot()
        assertEquals(2, snap.size)
        assertEquals("m-new", snap.first().model)
        assertTrue("视觉被否证应标记为存在矛盾证据", snap.first().hasContradiction)
        assertFalse(snap.last().hasContradiction)
    }

    @Test
    fun `空模型名不产生条目`() {
        assertNull(ModelEvidenceStore.evidenceFor("", "p1"))
    }
}
