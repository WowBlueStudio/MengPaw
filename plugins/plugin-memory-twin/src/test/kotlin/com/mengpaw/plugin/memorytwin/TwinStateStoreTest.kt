// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 同步状态持久化 (P1-4) — 重启后 `twin.status` 仍能回答"上次同步成没成"。
 */
class TwinStateStoreTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = File(System.getProperty("java.io.tmpdir"), "mengpaw-twin-state-${System.nanoTime()}")
        DataPaths.initialize(base.absolutePath)
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        base.deleteRecursively()
    }

    @Test
    fun `落盘后可原样读回`() {
        TwinStateStore.save(lastSyncAt = 1_700_000_000_000L, filesReceived = 3, conflicts = 1)
        val p = TwinStateStore.load()
        assertTrue(p.hasSynced)
        assertEquals(1_700_000_000_000L, p.lastSyncAt)
        assertEquals(3, p.filesReceived)
        assertEquals(1, p.conflicts)
        assertTrue(p.lastError.isBlank())
    }

    @Test
    fun `失败保存保留上次成功时间_只更新错误`() {
        TwinStateStore.save(lastSyncAt = 1_700_000_000_000L, filesReceived = 2, conflicts = 0)
        TwinStateStore.save(lastSyncAt = 0L, filesReceived = 0, conflicts = 0, lastError = "对端未响应")
        val p = TwinStateStore.load()
        assertEquals("失败不得抹掉上次成功时间", 1_700_000_000_000L, p.lastSyncAt)
        assertEquals("对端未响应", p.lastError)
    }

    @Test
    fun `无状态文件时返回空且不抛`() {
        val p = TwinStateStore.load()
        assertFalse(p.hasSynced)
        assertEquals(0L, p.lastSyncAt)
    }

    @Test
    fun `引擎启动时载入上次同步状态`() {
        TwinStateStore.save(lastSyncAt = 1_700_000_123_000L, filesReceived = 5, conflicts = 2)
        val engine = TwinSyncEngine(serverSupplier = { null }, transportSupplier = { null },
            agentName = "agent", deviceId = "dev-1", deviceName = "测试机")
        val st = engine.syncState.value
        assertEquals("重启后必须仍显示上次同步时间", 1_700_000_123_000L, st.lastSyncAt)
        assertEquals(5, st.lastFilesReceived)
        assertEquals(2, st.lastConflicts)
    }

    @Test
    fun `损坏的状态文件按无状态处理`() {
        TwinStateStore.save(lastSyncAt = 1L, filesReceived = 1, conflicts = 0)
        File(DataPaths.TWIN_DIR, "twin-state.json").writeText("{ 坏掉的 json")
        val p = TwinStateStore.load()
        assertFalse("坏文件不得抛异常，按无状态处理", p.hasSynced)
    }
}
