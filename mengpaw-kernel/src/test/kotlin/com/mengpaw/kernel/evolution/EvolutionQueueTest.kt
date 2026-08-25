// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * EvolutionQueue 静默分支进化队列回归 (v0.44):
 * 失败按模式去重 / 纠正不去重 / 待处理与移除 / 跨重启持久化。
 */
class EvolutionQueueTest {

    @Before
    fun setUp() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-evo-queue-${System.currentTimeMillis()}")
        tmp.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(tmp.absolutePath)
        EvolutionQueue.resetForTest()
    }

    @Test
    fun `failure enqueues once per pattern`() {
        assertTrue(EvolutionQueue.enqueueFailure("q1", "fs.cat /a", "ERR_IO", "m", "t", "s", "ctx"))
        assertFalse("同模式重复入队应被去重",
            EvolutionQueue.enqueueFailure("q1", "fs.cat /a", "ERR_IO", "m", "t", "s", "ctx"))
        assertTrue("同命令不同错误码算不同模式",
            EvolutionQueue.enqueueFailure("q1", "fs.cat /a", "ERR_PERMISSION", "m"))
        assertEquals("去重后应 2 条 (两种错误码)", 2, EvolutionQueue.pendingCount("q1"))
    }

    @Test
    fun `correction enqueues without dedup`() {
        EvolutionQueue.enqueueCorrection("q2", "不对", "ctx", "task")
        EvolutionQueue.enqueueCorrection("q2", "重做", "ctx", "task")
        assertEquals("纠正不去重", 2, EvolutionQueue.pendingCount("q2"))
    }

    @Test
    fun `removeProcessed clears handled items`() {
        EvolutionQueue.enqueueFailure("q3", "agent.ls", "ERR_IO", "m")
        val items = EvolutionQueue.pendingItems("q3")
        assertEquals(1, items.size)
        EvolutionQueue.removeProcessed("q3", items)
        assertFalse("处理后队列应清空", EvolutionQueue.hasPending("q3"))
    }

    @Test
    fun `queue persists across restart`() {
        EvolutionQueue.enqueueFailure("q4", "net.get", "ERR_TIMEOUT", "m")
        // 模拟重启: 清内存态, 保留磁盘
        EvolutionQueue.resetForTest()
        val items = EvolutionQueue.pendingItems("q4")
        assertEquals("跨重启队列应仍在", 1, items.size)
        assertEquals("net.get", items.first().command)
    }
}
