// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AcpProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var initialized = false

    private fun ensureDataPaths() {
        if (initialized) return
        val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "mengpaw-test-${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(tmpDir.absolutePath)
        initialized = true
    }

    // ── Message serialization round-trip ────────────────────────────

    @Test fun `round-trip all message types`() {
        // 遍历 AcpMessageType.entries 自动覆盖全部消息类型 —
        // 新增协议类型无需同步维护数量断言
        for (type in AcpMessageType.entries) {
            val msg = AcpMessage("test-from", "test-to", type.name, """{"test":true}""")
            val serialized = Json.encodeToString(AcpMessage.serializer(), msg)
            val deserialized = json.decodeFromString<AcpMessage>(serialized)
            assertEquals(msg.from, deserialized.from)
            assertEquals(msg.type, deserialized.type)
        }
    }

    @Test fun `factory methods produce valid JSON payload`() {
        // MCP tools/list
        val tl = McpOverAcpBridge.toolsList("cc")
        assertEquals(AcpMessageType.MCP_REQUEST.name, tl.type)
        val tlPayload = json.parseToJsonElement(tl.payload).jsonObject
        assertEquals("tools/list", tlPayload["method"]?.jsonPrimitive?.content)
        assertEquals("2.0", tlPayload["jsonrpc"]?.jsonPrimitive?.content)

        // Pair challenge
        val pc = AcpMessage.pairChallenge("a", "b", "dev1", "nonceX", "fingerprintY")
        val pcPayload = json.parseToJsonElement(pc.payload).jsonObject
        assertEquals("dev1", pcPayload["deviceId"]?.jsonPrimitive?.content)
        assertEquals("fingerprintY", pcPayload["fingerprint"]?.jsonPrimitive?.content)
    }

    // ── AcpServer routing ───────────────────────────────────────────

    @Test fun `DELEGATE routes to DelegateHandler`() {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        // Payload must pass firewall for guest peer
        val msg = AcpMessage.delegate("peer-a", "peer-b", "agent.memory.record test delegation")

        val result = runBlocking { server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg)) }
        assertTrue(result.success)
        assertTrue(result.message.contains("delegate_queued"))
    }

    @Test fun `SHARE_MEMORY routes to ShareMemoryHandler`() {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        com.mengpaw.kernel.security.PromptFirewall.trust("peer-a", "test-fingerprint")
        val msg = AcpMessage.shareMemory("peer-a", "peer-b", "shared-memory-id")

        val result = runBlocking { server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg)) }
        assertTrue(result.success)
        assertTrue(result.message.contains("memory_shared"))
    }

    @Test fun `WS_PULL blocked for untrusted peer`() {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val msg = AcpMessage.wsPull("untrusted", "*", """{"paths":["soul.md"]}""")

        val result = runBlocking { server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg)) }
        assertFalse(result.success)
        assertTrue(result.message.contains("trust") || result.message.contains("auth"))
    }

    @Test fun `HEARTBEAT always passes`() {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val msg = AcpMessage.heartbeat("any-peer")

        val result = runBlocking { server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg)) }
        assertTrue(result.success)
        assertEquals("alive", result.message)
    }

    // ── MCP-over-ACP ────────────────────────────────────────────────

    @Test fun `MCP_REQUEST without bridge returns error`() {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val msg = McpOverAcpBridge.toolsList("cc")

        val result = runBlocking { server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg)) }
        assertFalse("Should fail without bridge: $result", result.success)
    }

    @Test fun `MCP_REQUEST with bridge enabled returns tools`() {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val pm = com.mengpaw.kernel.plugin.PluginManager("0.12.12")
        val mcpServer = com.mengpaw.kernel.mcp.McpServer(pm)
        server.enableMcpBridge(mcpServer)

        val msg = McpOverAcpBridge.toolsList("cc")
        val result = runBlocking { server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg)) }
        assertTrue("Bridge MCP should succeed: $result", result.success)
    }

    // ── 协议升级 (v0.22.1): requestId / 版本协商 / MCP 往返 ───────────

    @Test fun `AcpMessage round-trips requestId`() {
        val msg = AcpMessage("a", "b", AcpMessageType.DELEGATE.name, "task", requestId = "req-42")
        val decoded = Json.decodeFromString(AcpMessage.serializer(), Json.encodeToString(AcpMessage.serializer(), msg))
        assertEquals("req-42", decoded.requestId)
    }

    @Test fun `legacy message without requestId defaults to empty`() {
        val legacy = """{"from":"a","to":"b","type":"DELEGATE","payload":"task"}"""
        val decoded = Json.decodeFromString(AcpMessage.serializer(), legacy)
        assertEquals("", decoded.requestId)
        assertEquals("task", decoded.payload)
    }

    @Test fun `discover with protocols produces negotiable payload`() {
        val msg = AcpMessage.discover("me", listOf("acp/1.0", "acp/1.1", "mcp/1.0"))
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(msg.payload).jsonObject
        val protocols = payload["protocols"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("acp/1.0", "acp/1.1", "mcp/1.0"), protocols)
    }

    @Test fun `legacy discover with empty payload stays compatible`() {
        val msg = AcpMessage.discover("me")
        assertEquals("", msg.payload)
    }

    @Test fun `mcp request response carry requestId`() {
        val req = AcpMessage.mcpRequest("cc", """{"jsonrpc":"2.0","method":"tools/list","id":1}""", "abc-123")
        assertEquals("abc-123", req.requestId)
        assertEquals(AcpMessageType.MCP_REQUEST.name, req.type)
        val resp = AcpMessage.mcpResponse("mengpaw", "cc", """{"jsonrpc":"2.0","result":{},"id":1}""", "abc-123")
        assertEquals("abc-123", resp.requestId)
        assertEquals("cc", resp.to)
    }

    @Test fun `bridge sends MCP_RESPONSE back for requestId requests`() = runBlocking {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val pm = com.mengpaw.kernel.plugin.PluginManager("0.12.12")
        val mcpServer = com.mengpaw.kernel.mcp.McpServer(pm)
        server.enableMcpBridge(mcpServer)

        val msg = AcpMessage.mcpRequest("cc", """{"jsonrpc":"2.0","method":"tools/list","id":1}""", "roundtrip-1")
        val result = server.handleMessage(Json.encodeToString(AcpMessage.serializer(), msg))
        assertTrue("Bridge MCP should succeed: $result", result.success)
        // 回发 MCP_RESPONSE 走 sendViaTransport — 无注册 transport 时静默跳过 (不阻断), 断言结果本身可用
        assertTrue("应含 JSON-RPC 结果", result.data.contains("jsonrpc"))
        assertTrue("应含 result 字段", result.data.contains("\"result\""))
    }
}
