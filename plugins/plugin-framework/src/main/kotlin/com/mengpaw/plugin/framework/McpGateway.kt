// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.ports.Ports
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 本机标准 MCP 网关 — 监听 127.0.0.1:9881 (Ports.MCP_LOCAL)。
 *
 * 任何 MCP 客户端 (Claude Code / Claude Desktop / 第三方) 经标准 JSON-RPC 直连
 * 调用 MengPaw 工具: POST /mcp body 直通内核 McpServer.handleRequest
 * (tools/list · tools/call · resources/list · prompts/list|get 全覆盖)。
 *
 * 复用 McpHttpServer (浏览器桥) 的裸 ServerSocket + 手写 HTTP 先例, 零新依赖。
 * 生命周期: FrameworkPlugin.onInstall 启动 / onUninstall 停止。
 */
object McpGateway {

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverThread: Thread? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        serverThread = Thread({ runServer() }, "mcp-gateway").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun runServer() {
        try {
            serverSocket = ServerSocket(Ports.MCP_LOCAL, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            android.util.Log.w("MengPaw", "MCP gateway bind failed: ${e.message}")
            running = false
            return
        }
        android.util.Log.i("MengPaw", "MCP gateway listening on 127.0.0.1:${Ports.MCP_LOCAL}")
        while (running) {
            val client = try { serverSocket?.accept() ?: continue } catch (e: Exception) {
                if (running) Thread.sleep(100)
                continue
            }
            Thread({ handle(client) }, "mcp-gateway-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1) ?: return

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val response: String
            val status: String
            when {
                method == "GET" && path == "/health" -> {
                    response = """{"ok":true,"status":"online"}"""
                    status = "200 OK"
                }
                method == "POST" && path == "/mcp" -> {
                    val body = CharArray(contentLength)
                    if (contentLength > 0) reader.read(body, 0, contentLength)
                    val mcpServer = com.mengpaw.kernel.mcp.McpServer(
                        com.mengpaw.kernel.plugin.PluginManager.globalInstance
                    )
                    response = mcpServer.handleRequest(String(body))
                    status = "200 OK"
                }
                else -> {
                    response = """{"jsonrpc":"2.0","error":{"code":-32601,"message":"Not found: $method $path"},"id":null}"""
                    status = "404 Not Found"
                }
            }

            writer.write("HTTP/1.1 $status\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${response.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(response)
            writer.flush()
        } catch (e: Exception) {
            android.util.Log.w("MengPaw", "MCP gateway request failed: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
