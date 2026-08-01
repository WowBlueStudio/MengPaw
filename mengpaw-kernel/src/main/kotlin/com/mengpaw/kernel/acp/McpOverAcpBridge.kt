// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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

        // 协议升级 (v0.22.1): 请求-响应一轮完成 — 回发 MCP_RESPONSE (requestId 关联)
        if (message.requestId.isNotBlank() && message.from != "*") {
            try {
                server.sendViaTransport(
                    AcpMessage.mcpResponse("mengpaw", message.from, response, message.requestId)
                )
            } catch (_: Exception) { /* 响应回发失败不阻断 (调用方仍可经 AcpResult 取结果) */ }
        }
        return AcpResult(true, "mcp_response", response)
    }

    companion object {
        /**
         * Wrap a raw MCP JSON-RPC request string into an ACP MCP_REQUEST message.
         * Use this from client code to build ACP messages.
         */
        fun wrapRequest(from: String, jsonRpcBody: String, requestId: String = ""): AcpMessage =
            AcpMessage(from, "*", AcpMessageType.MCP_REQUEST.name, jsonRpcBody, ttl = 1, requestId = requestId)

        /**
         * Build common MCP requests as ACP messages.
         */
        fun toolsList(from: String, requestId: String = ""): AcpMessage = wrapRequest(from,
            """{"jsonrpc":"2.0","method":"tools/list","id":1}""", requestId)

        fun resourcesList(from: String, requestId: String = ""): AcpMessage = wrapRequest(from,
            """{"jsonrpc":"2.0","method":"resources/list","id":2}""", requestId)

        fun promptsList(from: String, requestId: String = ""): AcpMessage = wrapRequest(from,
            """{"jsonrpc":"2.0","method":"prompts/list","id":3}""", requestId)

        /** tools/call — SECURITY: JsonObject 构造, 不做字符串插值 (防注入)。 */
        fun toolsCall(from: String, toolName: String, arguments: String = "{}", requestId: String = ""): AcpMessage {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "tools/call")
                put("id", 4)
                putJsonObject("params") {
                    put("name", toolName)
                    put("arguments", Json.parseToJsonElement(arguments.ifBlank { "{}" }))
                }
            }.toString()
            return wrapRequest(from, payload, requestId)
        }
    }
}
