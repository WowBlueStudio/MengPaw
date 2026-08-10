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
        // v0.35.4: 唯一目录 — 此前固定 mengpaw_pair_test, 全量 `./gradlew test`
        // 并行执行 debug/release 两个 test variant 时互删目录/写文件竞态
        // (偶发 "write failed" + pendingCount 错乱; 单独跑只跑一个 variant 全绿)
        DataPaths.initialize(
            File(System.getProperty("java.io.tmpdir"), "mengpaw_pair_test-${System.nanoTime()}").absolutePath
        )
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

    // ── v0.35.2 审查闭环: 过期清理 / 清除已处理 ─────────────────────

    @Test
    fun `cleanupExpired removes old processed but keeps pending`() {
        val old = System.currentTimeMillis() - 8 * 24L * 3600 * 1000L
        FrameworkPairStore.add(request().copy(requestId = "old-accepted", requestedAt = old,
            status = FrameworkPairStore.PairStatus.ACCEPTED))
        FrameworkPairStore.add(request().copy(requestId = "old-declined", requestedAt = old,
            status = FrameworkPairStore.PairStatus.DECLINED))
        FrameworkPairStore.add(request().copy(requestId = "pending-old"))
        FrameworkPairStore.add(request().copy(requestId = "recent-accepted",
            status = FrameworkPairStore.PairStatus.ACCEPTED))

        val removed = FrameworkPairStore.cleanupExpired(days = 7)
        assertEquals("仅过期已处理被清", 2, removed)
        assertEquals("待处理保留", 1, FrameworkPairStore.pending().size)
        assertNotNull("7 天内已处理保留", FrameworkPairStore.findByRequestId("recent-accepted"))
        assertNull("过期已处理清除", FrameworkPairStore.findByRequestId("old-accepted"))
    }

    @Test
    fun `clearProcessed keeps only pending`() {
        FrameworkPairStore.add(request().copy(requestId = "p1"))
        FrameworkPairStore.add(request().copy(requestId = "a1",
            status = FrameworkPairStore.PairStatus.ACCEPTED))
        FrameworkPairStore.add(request().copy(requestId = "d1",
            status = FrameworkPairStore.PairStatus.DECLINED))

        FrameworkPairStore.clearProcessed()
        assertEquals(1, FrameworkPairStore.loadAll().size)
        assertEquals(FrameworkPairStore.PairStatus.PENDING,
            FrameworkPairStore.loadAll()[0].status)
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
