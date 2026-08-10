// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * SwarmRuntimeStore 回归测试 (v0.35.5) —
 * 火种模式运行时持久化: 保存/读取往返、僵尸清理 (2h 无更新)、清除、损坏容错。
 */
class SwarmRuntimeStoreTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "swarm_runtime_test-${System.nanoTime()}")

    @Before
    fun init() {
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)
    }

    @After
    fun cleanup() {
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    private fun runtime(updatedAt: Long = System.currentTimeMillis()) = SwarmRuntimeStore.Runtime(
        task = "跨设备编队推演",
        startedAt = updatedAt - 1000,
        totalSteps = 60,
        consumedSteps = 12,
        subtasks = listOf(
            SwarmRuntimeStore.SubtaskState("t1", "扫描局域网", "VERIFIED", 0, "发现 2 台设备"),
            SwarmRuntimeStore.SubtaskState("t2", "发起配对", "RUNNING", 1, "")
        ),
        updatedAt = updatedAt
    )

    @Test
    fun 保存读取往返保真() {
        SwarmRuntimeStore.save(runtime())
        val loaded = SwarmRuntimeStore.load()
        assertEquals("跨设备编队推演", loaded?.task)
        assertEquals(60, loaded?.totalSteps)
        assertEquals(12, loaded?.consumedSteps)
        assertEquals(2, loaded?.subtasks?.size)
        assertEquals("VERIFIED", loaded?.subtasks?.get(0)?.status)
        assertEquals(1, loaded?.subtasks?.get(1)?.retries)
    }

    @Test
    fun 僵尸运行记录自动清理() {
        val stale = System.currentTimeMillis() - SwarmRuntimeStore.STALE_AFTER_MS - 1000
        SwarmRuntimeStore.save(runtime(updatedAt = stale))
        assertNull("超 2h 无更新应视为僵尸并清理", SwarmRuntimeStore.load())
    }

    @Test
    fun 清除后读取为空() {
        SwarmRuntimeStore.save(runtime())
        SwarmRuntimeStore.clear()
        assertNull(SwarmRuntimeStore.load())
    }

    @Test
    fun 损坏文件容错返回空() {
        val f = File(com.mengpaw.kernel.DataPaths.CONFIG, "swarm_runtime.json")
        f.parentFile?.mkdirs()
        f.writeText("{broken json")
        assertNull(SwarmRuntimeStore.load())
    }
}
