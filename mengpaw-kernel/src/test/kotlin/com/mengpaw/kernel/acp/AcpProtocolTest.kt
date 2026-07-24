// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
        val types = AcpMessageType.entries
        assertTrue("Should have 18 message types but has ${types.size}", types.size == 18)

        for (type in types) {
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

    @Test fun `LEDGER_BATCH blocked for untrusted peer`() {
        ensureDataPaths()
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val msg = AcpMessage.ledgerBatch("untrusted", "*", "[]", "hash1", "hash2")

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
}
