// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.SyncV3Messages
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.memory.SnapshotStore
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
 * TwinAcpHandler 消息处理测试 (插件零测试补齐)。
 *
 * - WS_MANIFEST 哈希比对: 对端哈希与本地一致 → 不重发; 缺失/不同 → 重发 (P1 截断比较回归)
 * - WS_MANIFEST 收发哈希形态统一: 对端清单用 16 字符 (TwinWorkspace.manifestHash) 构造,
 *   相同内容时 send 为空 (P0 回归 — 修复前每 60s 全量重推)
 * - WS_MANIFEST/WS_PULL 穿越路径消毒: ../ 条目被跳过 (P0 修复回归)
 * - TWIN_DELEGATE 信任门: 未配对拒绝, 配对后放行
 *
 * 跳过: handleRevoke (内含 android.util.Log, JVM stub 抛异常)。
 */
class TwinAcpHandlerTest {

    private lateinit var tempBase: File
    private lateinit var handler: TwinAcpHandler
    private lateinit var engine: TwinSyncEngine
    private lateinit var server: AcpServer

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-twin-acp-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        // 预置本地工作区文件
        File(DataPaths.AGENTS, "agent/workspace").mkdirs()
        File(DataPaths.AGENTS, "agent/workspace/doc.md").writeText("文档内容 v1")

        engine = TwinSyncEngine(serverSupplier = { null }, transportSupplier = { null },
            agentName = "agent", deviceId = "dev-1", deviceName = "测试机")
        handler = TwinAcpHandler(engine)
        server = AcpServer(AgentProfile(agentId = "agent", agentName = "agent"), sharedSecret = "test-secret")
        server.registerHandler(handler)
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    private fun localHashOf(relPath: String): String {
        val f = File(DataPaths.AGENTS, "agent/$relPath")
        return TwinWorkspace.fileHash(f)
    }

    // ── WS_MANIFEST: 哈希比对 ──────────────────────────────────

    @Test
    fun `WS-MANIFEST 对端哈希一致的文件不重发`() = runTest {
        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                put("workspace/doc.md", buildJsonObject {
                    // 线上形态: 发送端已归一为 16 字符 (TwinWorkspace.manifestHash)
                    put("hash", TwinWorkspace.manifestHash(localHashOf("workspace/doc.md")))
                    put("mtime", Json.parseToJsonElement("0"))
                })
            })
        }.toString()
        val msg = AcpMessage.wsManifest("peer-1", "agent", peerManifest)

        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val resp = Json.parseToJsonElement(result.data).jsonObject
        // send 应为空 — 本地与对端哈希一致, 无需重发
        assertTrue("哈希一致不应重发: ${resp["send"]}", resp["send"]!!.jsonObject.isEmpty())
        // request 也应为空 — 对端有的文件本地都有
        assertTrue(resp["request"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `WS-MANIFEST 对端清单携带相同内容的截断哈希时 send 为空 (P0 回归)`() = runTest {
        // P0 缺陷: 发送端把哈希截成 16 字符, 接收端却比完整 64 字符 → 永不相等,
        // 每个同步周期 (60s) 全量重推整个工作区。本用例按线上形态构造对端清单。
        val fullHash = localHashOf("workspace/doc.md")
        assertEquals("本地哈希应为完整 SHA-256", 64, fullHash.length)
        val peerHash = TwinWorkspace.manifestHash(fullHash)
        assertEquals(16, peerHash.length)

        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                put("workspace/doc.md", buildJsonObject {
                    put("hash", peerHash)
                    put("mtime", Json.parseToJsonElement("0"))
                })
            })
        }.toString()
        val msg = AcpMessage.wsManifest("peer-1", "agent", peerManifest)

        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val resp = Json.parseToJsonElement(result.data).jsonObject
        assertTrue("相同内容的截断哈希不得重推: ${resp["send"]}", resp["send"]!!.jsonObject.isEmpty())
        assertTrue(resp["request"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `WS-MANIFEST 对端哈希不同或缺失的文件重发`() = runTest {
        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                // doc.md: 对端哈希与本地不同 → 重发 (线上形态为 16 字符)
                put("workspace/doc.md", buildJsonObject {
                    put("hash", "0".repeat(TwinWorkspace.MANIFEST_HASH_CHARS))
                    put("mtime", Json.parseToJsonElement("0"))
                })
                // new.md: 对端有、本地无 → 进入 request
                put("workspace/new.md", buildJsonObject {
                    put("hash", "a".repeat(TwinWorkspace.MANIFEST_HASH_CHARS))
                    put("mtime", Json.parseToJsonElement("0"))
                })
            })
        }.toString()
        val msg = AcpMessage.wsManifest("peer-1", "agent", peerManifest)

        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val resp = Json.parseToJsonElement(result.data).jsonObject
        // P0: send 载荷必须是 {content, mtime} 对象 — 与接收端 parseManifestResponse 同形态
        val sentFile = resp["send"]!!.jsonObject["workspace/doc.md"]!!.jsonObject
        assertEquals("文档内容 v1", sentFile["content"]!!.jsonPrimitive.content)
        assertTrue("mtime 必须随内容下发 (冲突判定依赖它)", sentFile["mtime"]!!.jsonPrimitive.long > 0L)
        assertEquals(listOf("workspace/new.md"),
            resp["request"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `WS-MANIFEST 穿越路径条目被整体跳过 (P0 修复回归)`() = runTest {
        // 对端清单含 ../../secret.md — 必须不进 send 也不进 request
        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                put("../../secret.md", buildJsonObject {
                    put("hash", "b".repeat(TwinWorkspace.MANIFEST_HASH_CHARS))
                    put("mtime", Json.parseToJsonElement("0"))
                })
            })
        }.toString()
        val msg = AcpMessage.wsManifest("peer-1", "agent", peerManifest)

        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val resp = Json.parseToJsonElement(result.data).jsonObject
        val requestPaths = resp["request"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse("穿越路径不得出现在 request 中", requestPaths.any { it.contains("..") })
    }

    @Test
    fun `WS-MANIFEST 无效载荷返回失败结果`() = runTest {
        val msg = AcpMessage.wsManifest("peer-1", "agent", "not-json")
        val result = handler.handle(msg, server) as AcpResult
        assertFalse(result.success)
        assertEquals("invalid_manifest", result.message)
    }

    // ── WS_PULL: 路径消毒 ──────────────────────────────────────

    @Test
    fun `WS-PULL 返回合法路径的文件内容`() = runTest {
        val msg = AcpMessage.wsPull("peer-1", "agent",
            """{"paths":["workspace/doc.md"]}""")
        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val files = Json.parseToJsonElement(result.data).jsonObject["files"]!!.jsonObject
        assertEquals("文档内容 v1", files["workspace/doc.md"]!!.jsonPrimitive.content)
    }

    @Test
    fun `WS-PULL 穿越路径被拒绝不读文件 (P0 修复回归)`() = runTest {
        // 在沙箱外放一个诱饵文件 — 若被读到即为穿越成功
        val outside = File(tempBase, "secret.md").apply { writeText("绝密内容") }
        val msg = AcpMessage.wsPull("peer-1", "agent",
            """{"paths":["../${outside.name}"]}""")
        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val files = Json.parseToJsonElement(result.data).jsonObject["files"]!!.jsonObject
        assertTrue("穿越路径不得返回文件内容", files.isEmpty())
    }

    @Test
    fun `WS-PULL 不存在的合法路径静默跳过`() = runTest {
        val msg = AcpMessage.wsPull("peer-1", "agent",
            """{"paths":["workspace/nope.md"]}""")
        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val files = Json.parseToJsonElement(result.data).jsonObject["files"]!!.jsonObject
        assertTrue(files.isEmpty())
    }

    // ── TWIN_DELEGATE: 信任门 ──────────────────────────────────

    @Test
    fun `TWIN-DELEGATE 未配对 peer 被拒绝`() = runTest {
        val msg = AcpMessage.twinDelegate("stranger-${System.nanoTime()}", "agent", "帮我写报告")
        val result = handler.handle(msg, server) as AcpResult
        assertFalse(result.success)
        assertEquals("untrusted_delegate", result.message)
    }

    @Test
    fun `TWIN-DELEGATE 已配对 peer 放行`() = runTest {
        val peerId = "paired-${System.nanoTime()}"
        PromptFirewall.trust(peerId, "fp")
        try {
            val msg = AcpMessage.twinDelegate(peerId, "agent", "帮我写报告")
            val result = handler.handle(msg, server) as AcpResult
            assertTrue(result.success)
            assertEquals("delegate_queued", result.message)
        } finally {
            PromptFirewall.untrust(peerId)
        }
    }

    // ── 其它 ───────────────────────────────────────────────────

    @Test
    fun `HEARTBEAT 返回 alive 并更新对端活跃时间`() = runTest {
        engine.addManualPeer("hb-peer", name = "hb-peer")
        val msg = AcpMessage.heartbeat("hb-peer")
        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        assertEquals("alive", result.message)
        assertTrue(engine.getPeers().first { it.peerId == "hb-peer" }.online)
    }

    @Test
    fun `未知消息类型返回 null`() = runTest {
        val msg = AcpMessage("peer-1", "agent", AcpMessageType.DISCOVER.name, "")
        assertNull(handler.handle(msg, server))
    }

    // ── v0.3 同步消息 (P2) ─────────────────────────────────────

    private fun seedSnapshot(): Pair<String, String> {
        val blob = SnapshotStore.writeBlob("文档内容 v1".toByteArray(Charsets.UTF_8))
        val id = SnapshotStore.commit("ws-agent", mapOf("workspace/doc.md" to blob), "test", "agent", "dev-1")
        return id to blob
    }

    @Test
    fun `SYNC-HELLO 头相同返回 same true`() = runTest {
        val (id, _) = seedSnapshot()
        val msg = AcpMessage.syncHello("peer-1", "agent", "ws-agent", "peer-1", listOf(id), "0.3")
        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val ack = SyncV3Messages.parseAck(result.data)
        assertEquals(true, ack?.same)
        assertEquals(listOf(id), ack?.heads)
    }

    @Test
    fun `SYNC-HELLO 头不同返回对端缺的 tree 与 blob`() = runTest {
        val (id, blob) = seedSnapshot()
        val tree = requireNotNull(SnapshotStore.snapshot("ws-agent", id)?.tree)
        // 请求方 heads 为空 → 本机可达对象全部算缺失
        val msg = AcpMessage.syncHello("peer-1", "agent", "ws-agent", "peer-1", emptyList(), "0.3")
        val result = handler.handle(msg, server) as AcpResult
        val ack = SyncV3Messages.parseAck(result.data)
        assertEquals(false, ack?.same)
        assertEquals(listOf(id), ack?.heads)
        assertTrue("应列出 tree: ${ack?.missingTrees}", ack?.missingTrees?.contains(tree) == true)
        assertTrue("应列出 blob: ${ack?.missingBlobs}", ack?.missingBlobs?.contains(blob) == true)
    }

    @Test
    fun `TREE-WALK 返回清单与快照原始字节`() = runTest {
        val (id, blob) = seedSnapshot()
        val msg = AcpMessage.treeWalk("peer-1", "agent", "ws-agent", id)
        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val parsed = SyncV3Messages.parseTreeWalkResponse(result.data)
        assertEquals(mapOf("workspace/doc.md" to blob), parsed?.files)
        assertEquals(id, parsed?.snapshot?.id)
        assertNotNull("快照原始字节必须随响应下发", parsed?.snapshotRaw)
    }

    @Test
    fun `OBJ-GET 返回 base64 blob 内容`() = runTest {
        val (_, blob) = seedSnapshot()
        val msg = AcpMessage.objGet("peer-1", "agent", "ws-agent", listOf(blob))
        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val blobs = SyncV3Messages.parseObjGetResponse(result.data)
        assertEquals("文档内容 v1", String(blobs[blob] ?: ByteArray(0), Charsets.UTF_8))
    }

    @Test
    fun `v0_3 消息拒绝非本机命名空间`() = runTest {
        val msg = AcpMessage.syncHello("peer-1", "agent", "ws-other", "peer-1", emptyList(), "0.3")
        val result = handler.handle(msg, server) as AcpResult
        assertFalse(result.success)
        assertEquals("unknown_ns", result.message)
    }

    @Test
    fun `v0_3 消息坏载荷返回失败`() = runTest {
        val hello = handler.handle(AcpMessage("peer-1", "agent", AcpMessageType.SYNC_HELLO.name, "bad"), server) as AcpResult
        assertFalse(hello.success)
        assertEquals("invalid_hello", hello.message)
        val tree = handler.handle(AcpMessage("peer-1", "agent", AcpMessageType.TREE_WALK.name, "bad"), server) as AcpResult
        assertFalse(tree.success)
        assertEquals("invalid_tree_walk", tree.message)
        val obj = handler.handle(AcpMessage("peer-1", "agent", AcpMessageType.OBJ_GET.name, "bad"), server) as AcpResult
        assertFalse(obj.success)
        assertEquals("invalid_obj_get", obj.message)
    }
}
