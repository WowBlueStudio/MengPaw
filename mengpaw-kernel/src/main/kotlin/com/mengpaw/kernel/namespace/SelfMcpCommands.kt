// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * self.mcp 子命令执行器 — MCP 连接管理 (拆自 SelfExecutor, 400 行文件拆分)。
 * 经 [SelfExecutor.commands]["mcp"] 委托注册。
 */
internal class SelfMcpCommands {

    /** MCP connection management. Usage: self.mcp [connect|disconnect|status|call] */
    internal suspend fun mcp(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.ok(com.mengpaw.kernel.mcp.McpClient.statusReport() +
            "\n\n本机 MCP 网关 (9881): 需 Bearer token — `self.mcp token` 查看")
        val sub = args[0]
        return MCP_SUBCOMMANDS[sub]?.invoke(args, ctx)
            ?: ExecutionResult.fail("Usage: self.mcp connect|disconnect|status|call|token", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    private val MCP_SUBCOMMANDS: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "connect" to { a, _ ->
            if (a.size < 2) ExecutionResult.fail("Usage: self.mcp connect <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val cfg = com.mengpaw.kernel.mcp.McpClient.PRESETS[a[1]]
                if (cfg == null) ExecutionResult.fail("Unknown preset: ${a[1]}. Available: ${com.mengpaw.kernel.mcp.McpClient.PRESETS.keys}", errorCode = ErrorCodes.ERR_NOT_FOUND)
                else { com.mengpaw.kernel.mcp.McpClient.connect(a[1], cfg); ExecutionResult.ok("Connected to ${cfg.name}.") }
            }
        },
        "disconnect" to { a, _ ->
            if (a.size < 2) ExecutionResult.fail("Usage: self.mcp disconnect <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else { com.mengpaw.kernel.mcp.McpClient.disconnect(a[1]); ExecutionResult.ok("Disconnected: ${a[1]}") }
        },
        "status" to { _, _ -> ExecutionResult.ok(com.mengpaw.kernel.mcp.McpClient.statusReport()) },
        "token" to { _, _ ->
            val token = com.mengpaw.kernel.mcp.McpGatewayAuth.ensureToken()
            if (token.isBlank()) ExecutionResult.fail("MCP 网关未安装 (framework-plugin)", errorCode = ErrorCodes.ERR_NOT_FOUND)
            else ExecutionResult.ok(
                "本机 MCP 网关 (9881) Bearer token:\n$token\n\n" +
                "MCP 客户端请求头: Authorization: Bearer <token>\n" +
                "持久化: ${com.mengpaw.kernel.DataPaths.CONFIG}/mcp_gateway_token"
            )
        },
        "call" to { a, _ ->
            if (a.size < 4) ExecutionResult.fail("Usage: self.mcp call <connection-id> <tool-name> <args-json>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val conn = com.mengpaw.kernel.mcp.McpClient.listConnections().find { it.id == a[1] }
                if (conn == null) ExecutionResult.fail("Not connected: ${a[1]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
                else {
                    val toolArgs: Map<String, String> = try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(a.drop(3).joinToString(" "))
                        (json as? kotlinx.serialization.json.JsonObject)?.mapValues {
                            (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.value.toString()
                        } ?: emptyMap()
                    } catch (e: Exception) { emptyMap() }
                    ExecutionResult.ok(conn.callTool(a[2], toolArgs))
                }
            }
        }
    )
}
