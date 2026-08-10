// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FleetRuntimeStore 回归测试 (v0.36 深度进化) —
 * 指挥舰委派状态追踪 + 执行方回传地址记录 + 僵尸清理。
 */
class FleetRuntimeStoreTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "fleet_runtime_test-${System.nanoTime()}")

    @Before
    fun init() {
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)
        FleetRuntimeStore.clear()
    }

    @After
    fun cleanup() {
        FleetRuntimeStore.clear()
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    fun 指挥舰委派记录为SENT() {
        FleetRuntimeStore.startTask("fleet-001", "开发 APK", "坦克机", "mengpaw-abc-def")
        val t = FleetRuntimeStore.find("fleet-001")
        assertEquals("SENT", t?.status)
        assertEquals("坦克机", t?.peerName)
        assertEquals("开发 APK", t?.task)
    }

    @Test
    fun 结果回传更新为DONE并回收结果() {
        FleetRuntimeStore.startTask("fleet-002", "执行测试", "步兵机", "mengpaw-abc-def")
        val marked = FleetRuntimeStore.markDone("fleet-002", "12/12 用例通过", "mengpaw-xyz-789", success = true)
        assertTrue(marked)
        val t = FleetRuntimeStore.find("fleet-002")
        assertEquals("DONE", t?.status)
        assertEquals("12/12 用例通过", t?.result)
        assertEquals("mengpaw-xyz-789", t?.fromPeer)
    }

    @Test
    fun 失败回传标记FAILED() {
        FleetRuntimeStore.startTask("fleet-003", "编译", "坦克机", "mengpaw-abc-def")
        FleetRuntimeStore.markDone("fleet-003", "R8 混淆失败", "mengpaw-xyz-789", success = false)
        assertEquals("FAILED", FleetRuntimeStore.find("fleet-003")?.status)
    }

    @Test
    fun 未知委派ID拒绝回传() {
        assertFalse("未知 delegateId 不得更新", FleetRuntimeStore.markDone("ghost", "x", "peer", true))
    }

    @Test
    fun 执行方记录回传地址() {
        FleetRuntimeStore.recordIncoming("fleet-004", "回归测试", "mengpaw-abc-def", "192.168.2.9", 9876)
        val t = FleetRuntimeStore.find("fleet-004")
        assertEquals("192.168.2.9", t?.callbackAddress)
        assertEquals(9876, t?.callbackPort)
        assertEquals("mengpaw-abc-def", t?.fromPeer)
    }

    @Test
    fun 僵尸记录自动清理() {
        val file = File(com.mengpaw.kernel.DataPaths.CONFIG, "fleet_tasks.json")
        file.parentFile?.mkdirs()
        val stale = System.currentTimeMillis() - FleetRuntimeStore.STALE_AFTER_MS - 1000
        file.writeText("""[{"delegateId":"fleet-old","task":"旧任务","peerName":"坦克机","commander":"mengpaw-abc-def","createdAt":$stale,"status":"SENT","result":"","fromPeer":"","updatedAt":$stale,"callbackAddress":"","callbackPort":0}]""")
        assertNull("超 24h 无更新应清理", FleetRuntimeStore.find("fleet-old"))
    }
}
