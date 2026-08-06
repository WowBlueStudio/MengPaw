// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TwinSyncEngine 纯逻辑测试 (插件零测试补齐)。
 *
 * 只测不启动任何循环/网络/Android 的公开 API:
 * - peers 增删查 (P1 并发集合修复回归)
 * - QoS METERED 门控
 * - 服务未启动/传输未初始化时的失败结果语义
 *
 * 跳过: startAutoSync/心跳循环/syncWithPeer 全链路 (真实网络与文件副作用)。
 */
class TwinSyncEngineTest {

    private lateinit var tempBase: File
    private lateinit var engine: TwinSyncEngine

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-twin-engine-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        engine = TwinSyncEngine(serverSupplier = { null }, transportSupplier = { null },
            agentName = "agent", deviceId = "dev-1", deviceName = "测试机")
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    @Test
    fun `addManualPeer 生成确定 peerId 且在线`() {
        val peer = engine.addManualPeer("192.168.1.50", name = "书房机")
        assertEquals("书房机", peer.peerId)
        assertEquals("192.168.1.50", peer.address)
        assertTrue(peer.online)
        assertEquals(1, engine.getPeers().size)
    }

    @Test
    fun `addManualPeer 同名地址替换旧条目不重复`() {
        engine.addManualPeer("192.168.1.50", name = "书房机")
        engine.addManualPeer("192.168.1.50", name = "书房机")
        engine.addManualPeer("192.168.1.50", name = "书房机")  // 同地址同 id — 覆盖
        assertEquals(1, engine.getPeers().size)
    }

    @Test
    fun `updatePeers 合并发现结果并更新在线数`() {
        engine.updatePeers(listOf(
            TwinPeerInfo("p1", "节点1", "10.0.0.1", online = false),
            TwinPeerInfo("p2", "节点2", "10.0.0.2", online = false)
        ))
        assertEquals(2, engine.getPeers().size)
        assertEquals(2, engine.syncState.value.onlinePeers)
        assertEquals(2, engine.syncState.value.totalPeers)
        // 地址变更会更新
        engine.updatePeers(listOf(TwinPeerInfo("p1", "节点1", "10.0.0.99", online = false)))
        assertEquals("10.0.0.99", engine.getPeers().first { it.peerId == "p1" }.address)
    }

    @Test
    fun `getPeers 返回防御性副本`() {
        engine.addManualPeer("10.0.0.8", name = "副本")
        val snapshot = engine.getPeers()
        snapshot[0].agentName = "被篡改"
        assertEquals("副本", engine.getPeers()[0].agentName)  // 原引擎不受影响
    }

    @Test
    fun `onHeartbeatReceived 未知 peer 不崩溃且不新增`() {
        engine.onHeartbeatReceived("unknown-peer")
        assertTrue(engine.getPeers().isEmpty())
    }

    @Test
    fun `METERED 流量计费模式暂停自动同步`() = runTest {
        engine.qosLevel = TwinSyncEngine.QosLevel.METERED
        engine.addManualPeer("10.0.0.8", name = "m")
        val results = engine.syncWithAllPeers()
        assertEquals(1, results.size)
        assertTrue(results[0].error!!.contains("按流量计费"))
        assertFalse(results[0].filesReceived > 0)
    }

    @Test
    fun `syncWithPeer 服务未启动返回明确错误`() = runTest {
        engine.addManualPeer("10.0.0.8", name = "m")
        val result = engine.syncWithPeer("m")
        assertEquals("ACP 服务未启动", result.error)
        assertEquals(0, result.filesReceived)
    }

    @Test
    fun `updatePeers 后 syncState 反映对端数量`() {
        assertEquals(0, engine.syncState.value.totalPeers)
        engine.updatePeers(listOf(
            TwinPeerInfo("p1", "n1", "10.0.0.1", online = false),
            TwinPeerInfo("p2", "n2", "10.0.0.2", online = false),
            TwinPeerInfo("p3", "n3", "10.0.0.3", online = false)
        ))
        assertEquals(3, engine.syncState.value.totalPeers)
    }
}
