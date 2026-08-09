// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.DataPaths
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 框架通讯录配对 (v0.35.1 请求-同意流程) 单测:
 * FrameworkPairStore 持久化/计数/状态 + FrameworkPairHandler 收发处理。
 */
class FrameworkPairTest {

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_pair_test")
        File(DataPaths.BASE).deleteRecursively()
    }

    private fun request() = FrameworkPairStore.PairRequest(
        requestId = "req-1",
        fromFingerprint = "mengpaw|aa:bb:cc:dd:ee:ff",
        fromName = "书房平板",
        fromAddress = "192.168.2.34",
        fromPort = 9876
    )

    // ── Store: 持久化/计数/状态 ──────────────────────────────────────

    @Test
    fun `add increments pendingCount and persists`() {
        FrameworkPairStore.add(request())
        assertEquals("pending 计数 = 1", 1, FrameworkPairStore.pendingCount)
        val loaded = FrameworkPairStore.pending()
        assertEquals(1, loaded.size)
        assertEquals("书房平板", loaded[0].fromName)
        assertEquals(FrameworkPairStore.PairStatus.PENDING, loaded[0].status)
    }

    @Test
    fun `update to accepted drops pendingCount`() {
        FrameworkPairStore.add(request())
        FrameworkPairStore.update("req-1") {
            it.copy(status = FrameworkPairStore.PairStatus.ACCEPTED, read = true)
        }
        assertEquals("已接受不再计入 pending", 0, FrameworkPairStore.pendingCount)
        assertEquals(FrameworkPairStore.PairStatus.ACCEPTED,
            FrameworkPairStore.findByRequestId("req-1")?.status)
    }

    @Test
    fun `duplicate requestId is replaced not duplicated`() {
        FrameworkPairStore.add(request())
        FrameworkPairStore.add(request().copy(requestedAt = 999L))
        assertEquals(1, FrameworkPairStore.pending().size)
        assertEquals(1, FrameworkPairStore.pendingCount)
    }

    // ── Handler: REQUEST 落盘 + ACCEPT 双向入册 ──────────────────────

    @Test
    fun `handler records pair request`() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FrameworkPairHandler()
        val msg = AcpMessage.frameworkPairRequest(
            from = "mengpaw-abc-def", to = "*", requestId = "req-h1",
            fingerprint = "mengpaw|aa:bb:cc:dd:ee:ff", displayName = "书房平板",
            address = "192.168.2.34", port = 9876
        )
        val result = kotlinx.coroutines.runBlocking { handler.handle(msg, server) }
        assertNotNull("REQUEST 应被处理", result)
        assertTrue("处理应成功", result!!.success)
        assertEquals("收到请求 → 红点计数 1", 1, FrameworkPairStore.pendingCount)
        assertEquals("书房平板", FrameworkPairStore.findByRequestId("req-h1")?.fromName)
    }

    @Test
    fun `handler accept adds requester to peer store`() {
        FrameworkPairStore.add(request())
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FrameworkPairHandler()
        val msg = AcpMessage.frameworkPairResponse(
            from = "mengpaw-123-456", to = "*", requestId = "req-1", accepted = true,
            fingerprint = "mengpaw|11:22:33:44:55:66", displayName = "客厅手机",
            address = "192.168.2.9", port = 9876
        )
        val result = kotlinx.coroutines.runBlocking { handler.handle(msg, server) }
        assertTrue("ACCEPT 应被处理", result?.success == true)
        // 发起方视角: 接受方已入册
        val peer = FrameworkPeerStore.findByFingerprint("mengpaw|11:22:33:44:55:66")
        assertNotNull("接受方应入册通讯录", peer)
        assertEquals("客厅手机", peer?.name)
    }
}
