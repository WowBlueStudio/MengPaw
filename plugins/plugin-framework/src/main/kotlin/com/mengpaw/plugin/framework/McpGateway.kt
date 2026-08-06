// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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

    /** MCP 请求体大小上限 (P2 修复: 原实现按 Content-Length 无上限 new CharArray,
     *  恶意客户端可声称超大长度撑爆内存)。超限/非法长度直接 413 拒绝。 */
    internal const val MAX_MCP_BODY_BYTES = 4 * 1024 * 1024

    /**
     * 解析 Content-Length 头 (大小写不敏感)。缺失/非法/负数一律返回 null,
     * 由 [routeRejection] 以 413 拒绝 — 对齐 P2 注释"非法长度直接 413"的意图
     * (原实现 toIntOrNull ?: 0 把非法值当 0 受理, 与上限检查形同虚设)。
     * internal 为测试可见性。
     */
    internal fun parseContentLength(line: String?): Int? {
        if (line == null || !line.startsWith("Content-Length:", ignoreCase = true)) return null
        return line.substringAfter(":").trim().toIntOrNull()?.takeIf { it >= 0 }
    }

    /**
     * 纯路由判定 (internal 为测试可见性): 返回 null 表示 POST /mcp 且长度合法,
     * 请求应被受理 (由调用方读取 body); 否则返回 (HTTP 状态行, 响应体)。
     */
    internal fun routeRejection(method: String, path: String, contentLength: Int?): Pair<String, String>? {
        if (method == "GET" && path == "/health") {
            return "200 OK" to """{"ok":true,"status":"online"}"""
        }
        if (method == "POST" && path == "/mcp" && contentLength != null && contentLength in 0..MAX_MCP_BODY_BYTES) {
            return null // 受理
        }
        if (method == "POST" && path == "/mcp") {
            // 请求体超限 (或非法/缺失 Content-Length) — 拒绝, 不分配内存
            return "413 Payload Too Large" to
                """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Request body exceeds ${MAX_MCP_BODY_BYTES / (1024 * 1024)}MB limit"},"id":null}"""
        }
        return "404 Not Found" to """{"jsonrpc":"2.0","error":{"code":-32601,"message":"Not found: $method $path"},"id":null}"""
    }

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

            var contentLength: Int? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                parseContentLength(line)?.let { contentLength = it }
            }

            val rejection = routeRejection(method, path, contentLength)
            val response: String
            val status: String
            if (rejection != null) {
                response = rejection.second
                status = rejection.first
            } else {
                // 受理: 读取请求体 (长度已由 routeRejection 限定 0..MAX) 并转交内核 MCP Server
                val len = contentLength ?: 0
                val body = CharArray(len)
                if (len > 0) reader.read(body, 0, len)
                val mcpServer = com.mengpaw.kernel.mcp.McpServer(
                    com.mengpaw.kernel.plugin.PluginManager.globalInstance
                )
                response = mcpServer.handleRequest(String(body))
                status = "200 OK"
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
