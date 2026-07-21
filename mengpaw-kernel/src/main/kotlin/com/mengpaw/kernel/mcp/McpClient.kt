// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.mcp

import kotlinx.serialization.json.*

/**
 * MCP Client — connects MengPaw to external MCP Servers.
 *
 * ## Outbound (MengPaw → External)
 * Connects to:
 * - OpenClaw (Mac/Windows Agent framework via MCP stdio)
 * - QwenPaw (通义千问 Agent via MCP HTTP)
 * - Microsoft Office (Word/Excel/PPT via MCP Bridge)
 * - Any MCP-compatible server
 *
 * ## Inbound (External → MengPaw)
 * Already implemented: McpServer.kt exposes MengPaw commands as MCP Tools.
 * External MCP Clients (Claude Desktop, OpenClaw) can call MengPaw.
 *
 * ## Connection Presets
 */
object McpClient {
    private val clients = mutableMapOf<String, McpConnection>()

    /** Pre-configured connections to popular frameworks. */
    val PRESETS = mapOf(
        "openclaw" to McpConnectionConfig(
            name = "OpenClaw",
            transport = "stdio",
            command = "openclaw",
            args = listOf("mcp-serve"),
            description = "OpenClaw Agent 框架 — Mac/Windows 桌面自动化"
        ),
        "qwenpaw" to McpConnectionConfig(
            name = "QwenPaw",
            transport = "http",
            url = "http://localhost:9877/mcp",
            description = "通义千问 Agent 框架 — 中文 AI 助手"
        ),
        "office-word" to McpConnectionConfig(
            name = "Microsoft Word",
            transport = "http",
            url = "http://localhost:9878/mcp/word",
            description = "Word 文档编辑 — 创建/修改/格式化 .docx"
        ),
        "office-excel" to McpConnectionConfig(
            name = "Microsoft Excel",
            transport = "http",
            url = "http://localhost:9878/mcp/excel",
            description = "Excel 表格操作 — 数据读写/公式/图表"
        ),
        "office-ppt" to McpConnectionConfig(
            name = "Microsoft PowerPoint",
            transport = "http",
            url = "http://localhost:9878/mcp/ppt",
            description = "PPT 演示文稿 — 创建/编辑/排版"
        )
    )

    /** SECURITY: Whitelist of known-safe stdio commands. */
    private val ALLOWED_STDIO_COMMANDS = setOf("openclaw", "python", "python3", "node")

    /** Connect to a preset or custom MCP Server. */
    fun connect(id: String, config: McpConnectionConfig): McpConnection {
        // SECURITY: Validate stdio commands against allowlist
        if (config.transport == "stdio" && config.command.isNotBlank()) {
            val cmd = config.command.split(" ").first()
            if (cmd !in ALLOWED_STDIO_COMMANDS && !java.io.File(config.command).isAbsolute) {
                throw SecurityException(
                    "MCP stdio command '$config.command' not allowed. " +
                    "Use an absolute path or one of: ${ALLOWED_STDIO_COMMANDS.joinToString()}"
                )
            }
        }
        val conn = McpConnection(id, config)
        clients[id] = conn
        return conn
    }

    /** List all active connections. */
    fun listConnections(): List<McpConnection> = clients.values.toList()

    /** Disconnect from a server. */
    fun disconnect(id: String) { clients.remove(id) }

    /** Get connection statuses for CLI display. */
    fun statusReport(): String = buildString {
        appendLine("# MCP 连接状态")
        PRESETS.forEach { (id, cfg) ->
            val conn = clients[id]
            val status = if (conn != null) "✅ 已连接" else "⏳ 未连接"
            appendLine("- $status ${cfg.name} ($id): ${cfg.description}")
        }
        appendLine()
        appendLine("连接命令: `self.mcp connect <id>`")
        appendLine("示例: `self.mcp connect openclaw`")
    }
}

data class McpConnectionConfig(
    val name: String,
    val transport: String, // "stdio" or "http"
    val command: String = "",
    val args: List<String> = emptyList(),
    val url: String = "",
    val description: String = ""
)

class McpConnection(val id: String, val config: McpConnectionConfig) {
    suspend fun callTool(toolName: String, arguments: Map<String, String>): String {
        return try {
            when (config.transport) {
                "http" -> callHttp(toolName, arguments)
                "stdio" -> callStdio(toolName, arguments)
                else -> "Error: unsupported transport ${config.transport}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private suspend fun callHttp(tool: String, args: Map<String, String>): String {
        val url = java.net.URL(config.url)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val req = buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "tools/call")
            put("id", System.currentTimeMillis().toString())
            putJsonObject("params") { put("name", tool); putJsonObject("arguments") { args.forEach { (k, v) -> put(k, v) } } }
        }
        conn.outputStream.write(req.toString().toByteArray())
        val result = conn.inputStream.bufferedReader().readText().take(5000)
        conn.disconnect()
        return result
    }

    private suspend fun callStdio(tool: String, args: Map<String, String>): String {
        val pb = ProcessBuilder(config.command).apply { this.command().addAll(config.args) }
        val proc = pb.start()
        val req = buildJsonObject {
            put("method", "tools/call")
            putJsonObject("params") { put("name", tool); putJsonObject("arguments") { args.forEach { (k, v) -> put(k, v) } } }
        }
        proc.outputStream.write("${req}\n".toByteArray()); proc.outputStream.flush()
        return proc.inputStream.bufferedReader().readText().take(5000)
    }
}
