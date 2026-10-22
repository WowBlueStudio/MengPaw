// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议 v0.3 载荷与鉴权测试 (P2, docs/memory-git-sync-design.md §4.1/§4.3) —
 * 四消息工厂/解析往返 + 未配对拒绝 + IP 绑定要求 + 命名空间白名单。
 */
class SyncV3MessagesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    // ── SYNC_HELLO ─────────────────────────────────────────────────

    @Test
    fun `SYNC_HELLO 载荷字段与设计 4_1 一致`() {
        val msg = AcpMessage.syncHello("dev-a", "peer-b", "ws-agent", "dev-a", listOf(shaA, shaB))
        assertEquals(AcpMessageType.SYNC_HELLO.name, msg.type)
        val payload = json.parseToJsonElement(msg.payload).jsonObject
        assertEquals("0.3", payload["proto"]?.jsonPrimitive?.content)
        assertEquals("dev-a", payload["deviceId"]?.jsonPrimitive?.content)
        assertEquals("ws-agent", payload["ns"]?.jsonPrimitive?.content)
        assertEquals(listOf(shaA, shaB), payload["heads"]?.let { el ->
            (el as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
        })
        val parsed = SyncV3Messages.parseHello(msg.payload)
        assertEquals(SyncHelloExpectation("0.3", "dev-a", "ws-agent", listOf(shaA, shaB)), parsed?.let {
            SyncHelloExpectation(it.proto, it.deviceId, it.ns, it.heads)
        })
    }

    @Test
    fun `SYNC_HELLO 缺字段或坏 JSON 返回 null`() {
        assertNull(SyncV3Messages.parseHello("not-json"))
        assertNull(SyncV3Messages.parseHello("""{"proto":"0.3"}"""))
        assertNull(SyncV3Messages.parseHello("""{"proto":"0.3","deviceId":"d"}"""))
    }

    @Test
    fun `SYNC_HELLO 过滤非 64 位对象 id`() {
        val payload = SyncV3Messages.helloPayload("0.3", "d", "dev", listOf(shaA, "../evil", "zzz"))
        assertEquals(listOf(shaA), SyncV3Messages.parseHello(payload)?.heads)
    }

    private data class SyncHelloExpectation(
        val proto: String,
        val deviceId: String,
        val ns: String,
        val heads: List<String>
    )

    // ── SYNC_ACK ───────────────────────────────────────────────────

    @Test
    fun `SYNC_ACK 往返保留 same 与缺失对象`() {
        val msg = AcpMessage.syncAck("peer-b", "dev-a", same = false,
            heads = listOf(shaA), missingTrees = listOf(shaB), missingBlobs = listOf(shaA))
        val ack = SyncV3Messages.parseAck(msg.payload)
        assertNotNull(ack)
        assertEquals(false, ack?.same)
        assertEquals(listOf(shaA), ack?.heads)
        assertEquals(listOf(shaB), ack?.missingTrees)
        assertEquals(listOf(shaA), ack?.missingBlobs)
    }

    @Test
    fun `SYNC_ACK 相同头只回 same true`() {
        val payload = SyncV3Messages.ackPayload(true, listOf(shaA), emptyList(), emptyList())
        val ack = SyncV3Messages.parseAck(payload)
        assertEquals(true, ack?.same)
        assertTrue(ack?.missingTrees?.isEmpty() == true)
        assertTrue(ack?.missingBlobs?.isEmpty() == true)
    }

    // ── TREE_WALK ──────────────────────────────────────────────────

    @Test
    fun `TREE_WALK 请求与响应往返 含快照原始字节`() {
        val req = AcpMessage.treeWalk("dev-a", "peer-b", "ws-agent", shaA)
        val parsed = SyncV3Messages.parseTreeWalk(req.payload)
        assertEquals("ws-agent", parsed?.ns)
        assertEquals(shaA, parsed?.sha)

        val raw = byteArrayOf(1, 2, 3, 4)
        val meta = SyncV3Messages.SnapshotMeta(
            id = shaA, tree = shaB, parents = emptyList(), lamport = 7, wall = 123,
            device = "dev-b", author = "agent", message = "合并"
        )
        val resp = SyncV3Messages.treeWalkResponsePayload(
            meta, shaB, mapOf("soul.md" to shaB), raw
        )
        val back = SyncV3Messages.parseTreeWalkResponse(resp)
        assertEquals(meta, back?.snapshot)
        assertEquals(mapOf("soul.md" to shaB), back?.files)
        assertTrue(back?.snapshotRaw?.contentEquals(raw) == true)
    }

    @Test
    fun `TREE_WALK 响应跳过非法 blob id`() {
        val resp = SyncV3Messages.treeWalkResponsePayload(null, shaB, emptyMap(), null)
        val ok = SyncV3Messages.parseTreeWalkResponse(resp)
        assertNull(ok?.snapshot)
        assertTrue(ok?.files?.isEmpty() == true)
        val bad = """{"files":{"soul.md":"../evil","a.md":"${shaA}"}}"""
        assertEquals(mapOf("a.md" to shaA), SyncV3Messages.parseTreeWalkResponse(bad)?.files)
    }

    // ── OBJ_GET ────────────────────────────────────────────────────

    @Test
    fun `OBJ_GET 批量取 blob 往返 base64`() {
        val req = AcpMessage.objGet("dev-a", "peer-b", "dev", listOf(shaA, shaB))
        assertEquals(AcpMessageType.OBJ_GET.name, req.type)
        assertEquals(listOf(shaA, shaB), SyncV3Messages.parseObjGet(req.payload)?.blobs)

        val content = "记忆内容".toByteArray(Charsets.UTF_8)
        val resp = SyncV3Messages.objGetResponsePayload(mapOf(shaA to content))
        val back = SyncV3Messages.parseObjGetResponse(resp)
        assertTrue(back[shaA]?.contentEquals(content) == true)
    }

    @Test
    fun `OBJ_GET 响应跳过非法 id 与坏 base64`() {
        val bad = """{"blobs":{"../x":"AAAA","${shaA}":"!!!not-base64"}}"""
        assertTrue(SyncV3Messages.parseObjGetResponse(bad).isEmpty())
    }

    // ── 命名空间 ───────────────────────────────────────────────────

    @Test
    fun `命名空间白名单只认 ws-本机 agent 与 dev`() {
        assertTrue(SyncV3Messages.isAllowedNamespace("ws-agent", "agent"))
        assertTrue(SyncV3Messages.isAllowedNamespace("dev", "agent"))
        assertFalse(SyncV3Messages.isAllowedNamespace("ws-other", "agent"))
        assertFalse(SyncV3Messages.isAllowedNamespace("..", "agent"))
        assertFalse(SyncV3Messages.isAllowedNamespace("", "agent"))
    }

    // ── 鉴权落点 ───────────────────────────────────────────────────

    @Test
    fun `四个新类型对未配对 peer 一律 auth_required`() = runBlocking {
        val server = AcpServer(AgentProfile(agentId = "agent", agentName = "agent"), sharedSecret = "test")
        val messages = listOf(
            AcpMessage.syncHello("evil-peer", "agent", "ws-agent", "evil", emptyList()),
            AcpMessage.syncAck("evil-peer", "agent", false, emptyList(), emptyList(), emptyList()),
            AcpMessage.treeWalk("evil-peer", "agent", "ws-agent", shaA),
            AcpMessage.objGet("evil-peer", "agent", "ws-agent", listOf(shaA))
        )
        for (msg in messages) {
            val result = server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg))
            assertFalse("${msg.type} 未配对必须拒绝", result.success)
            assertTrue("${msg.type} 应返回 auth_required: ${result.message}",
                result.message.contains("auth_required"))
        }
    }

    @Test
    fun `四个新类型与 v0_2 同属 IP 绑定敏感类型`() {
        val server = AcpServer(AgentProfile(agentId = "agent", agentName = "agent"), sharedSecret = "test")
        for (type in listOf("SYNC_HELLO", "SYNC_ACK", "TREE_WALK", "OBJ_GET")) {
            // 每个类型用独立 peer — 避免前一轮建立的绑定影响"拒绝不建立绑定"的断言
            val peer = "peer-$type"
            assertFalse("$type 未绑定 IP 必须拒绝", authorizeAndBind(server, type, peer, "1.1.1.1"))
            assertFalse("被拒绝不得建立绑定", server.isPeerFromBoundIp(peer, "1.1.1.1"))
            assertTrue("HEARTBEAT 负责建立绑定", authorizeAndBind(server, "HEARTBEAT", peer, "1.1.1.1"))
            assertTrue("$type 绑定后放行", authorizeAndBind(server, type, peer, "1.1.1.1"))
        }
    }
}
