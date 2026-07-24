// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.mcp.McpServer
import kotlinx.serialization.json.*

/**
 * MCP-over-ACP bridge — wraps MCP JSON-RPC 2.0 requests in ACP messages.
 *
 * This enables external clients (Claude Code, other MCP tools) to discover and
 * invoke MengPaw Agent tools through the ACP transport layer.
 *
 * ## Architecture
 * ```
 * MCP Client                    ACP Transport                MengPaw Agent
 *     │                              │                            │
 *     │  JSON-RPC {tools/list}       │                            │
 *     │  ──────────────────────────→ │                            │
 *     │                              │  MCP_REQUEST message       │
 *     │                              │  ────────────────────────→ │
 *     │                              │                            │ McpServer.handleRequest()
 *     │                              │  MCP_RESPONSE message      │
 *     │                              │  ←──────────────────────── │
 *     │  JSON-RPC {tools: [...]}     │                            │
 *     │  ←────────────────────────── │                            │
 * ```
 *
 * ## Usage (from Claude Code via ACP)
 * ```json
 * {"from":"claude-code","to":"*","type":"MCP_REQUEST",
 *  "payload":"{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}"}
 * ```
 */
class McpOverAcpBridge(
    private val mcpServer: McpServer
) : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(
        AcpMessageType.MCP_REQUEST
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult {
        // Parse MCP JSON-RPC request from ACP payload
        val rpcRequest = try {
            json.parseToJsonElement(message.payload).jsonObject
        } catch (_: Exception) {
            return AcpResult(false, "invalid_json_rpc", "Payload must be valid JSON-RPC 2.0")
        }

        val method = rpcRequest["method"]?.jsonPrimitive?.content
            ?: return AcpResult(false, "missing_method")

        // Process through McpServer
        val response = mcpServer.handleRequest(message.payload)

        return AcpResult(true, "mcp_response", response)
    }

    companion object {
        /**
         * Wrap a raw MCP JSON-RPC request string into an ACP MCP_REQUEST message.
         * Use this from client code to build ACP messages.
         */
        fun wrapRequest(from: String, jsonRpcBody: String): AcpMessage =
            AcpMessage(from, "*", AcpMessageType.MCP_REQUEST.name, jsonRpcBody, ttl = 1)

        /**
         * Build common MCP requests as ACP messages.
         */
        fun toolsList(from: String): AcpMessage = wrapRequest(from,
            """{"jsonrpc":"2.0","method":"tools/list","id":1}""")

        fun resourcesList(from: String): AcpMessage = wrapRequest(from,
            """{"jsonrpc":"2.0","method":"resources/list","id":2}""")

        fun promptsList(from: String): AcpMessage = wrapRequest(from,
            """{"jsonrpc":"2.0","method":"prompts/list","id":3}""")

        fun toolsCall(from: String, toolName: String, arguments: String = "{}"): AcpMessage =
            wrapRequest(from,
                """{"jsonrpc":"2.0","method":"tools/call","id":4,"params":{"name":"$toolName","arguments":$arguments}}""")
    }
}
