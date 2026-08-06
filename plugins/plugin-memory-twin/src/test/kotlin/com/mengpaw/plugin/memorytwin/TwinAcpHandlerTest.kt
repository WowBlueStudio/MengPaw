// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                    put("hash", localHashOf("workspace/doc.md"))   // 完整 64 位哈希 — 一致
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
    fun `WS-MANIFEST 对端哈希不同或缺失的文件重发`() = runTest {
        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                // doc.md: 对端哈希与本地不同 → 重发
                put("workspace/doc.md", buildJsonObject {
                    put("hash", "0".repeat(64))
                    put("mtime", Json.parseToJsonElement("0"))
                })
                // new.md: 对端有、本地无 → 进入 request
                put("workspace/new.md", buildJsonObject {
                    put("hash", "a".repeat(64))
                    put("mtime", Json.parseToJsonElement("0"))
                })
            })
        }.toString()
        val msg = AcpMessage.wsManifest("peer-1", "agent", peerManifest)

        val result = handler.handle(msg, server) as AcpResult
        assertTrue(result.success)
        val resp = Json.parseToJsonElement(result.data).jsonObject
        assertEquals("文档内容 v1", resp["send"]!!.jsonObject["workspace/doc.md"]!!.jsonPrimitive.content)
        assertEquals(listOf("workspace/new.md"),
            resp["request"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `WS-MANIFEST 穿越路径条目被整体跳过 (P0 修复回归)`() = runTest {
        // 对端清单含 ../../secret.md — 必须不进 send 也不进 request
        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                put("../../secret.md", buildJsonObject {
                    put("hash", "b".repeat(64))
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
}
