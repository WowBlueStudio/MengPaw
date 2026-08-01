// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.mcp

import com.mengpaw.kernel.ports.Ports
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 设备内 MCP 桥 HTTP server — 监听 127.0.0.1:9880 (Ports.BROWSER_MCP)。
 *
 * 让 Shell (MengPaw) 进程通过 HTTP 调用浏览器进程内的 MCP 工具。
 * 复用内核 AcpHttpTransport 的裸 ServerSocket + 手写 HTTP/1.1 先例 (零新依赖,
 * Android 无 com.sun.net.httpserver)。仅回环地址, 不暴露到局域网。
 *
 * 端点:
 * - GET  /health → {"ok":true,"status":"online","tools":6}
 * - POST /mcp   body {"tool":"browser_navigate","args":{"url":"..."}} → 工具结果 JSON
 *
 * 生命周期: BrowserActivity onCreate 启动 / onDestroy 停止。
 */
object McpHttpServer {

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverThread: Thread? = null

    /** 工具执行器 — 由 BrowserActivity 注入 (内部处理主线程切换)。 */
    @Volatile private var toolHandler: ((String, Map<String, String>) -> String)? = null

    val isRunning: Boolean get() = running

    /** 启动 HTTP server (幂等)。 */
    fun start(handler: (String, Map<String, String>) -> String) {
        if (running) return
        toolHandler = handler
        running = true
        serverThread = Thread({ runServer() }, "mcp-http-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        toolHandler = null
    }

    private fun runServer() {
        try {
            serverSocket = ServerSocket(Ports.BROWSER_MCP, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            android.util.Log.w("MengPaw", "MCP server bind failed: ${e.message}")
            running = false
            return
        }
        android.util.Log.i("MengPaw", "MCP server listening on 127.0.0.1:${Ports.BROWSER_MCP}")
        while (running) {
            val client = try { serverSocket?.accept() ?: continue } catch (e: Exception) {
                if (running) Thread.sleep(100)
                continue
            }
            Thread({ handle(client) }, "mcp-http-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            // ── 请求行 + 头 ──
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

            // ── 响应 ──
            val response: String
            val status: String
            when {
                method == "GET" && path == "/health" -> {
                    response = """{"ok":true,"status":"online","tools":6}"""
                    status = "200 OK"
                }
                method == "POST" && path == "/mcp" -> {
                    val body = CharArray(contentLength)
                    if (contentLength > 0) reader.read(body, 0, contentLength)
                    response = dispatchMcp(String(body))
                    status = "200 OK"
                }
                else -> {
                    response = """{"ok":false,"error":"Not found: $method $path"}"""
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
            android.util.Log.w("MengPaw", "MCP request failed: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun dispatchMcp(body: String): String {
        val handler = toolHandler ?: return """{"ok":false,"error":"MCP handler not bound"}"""
        return try {
            val req = JSONObject(body)
            val tool = req.optString("tool")
            val argsObj = req.optJSONObject("args")
            val argMap = HashMap<String, String>()
            if (argsObj != null) {
                val it = argsObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    argMap[k] = argsObj.optString(k, "")
                }
            }
            handler(tool, argMap)
        } catch (e: Exception) {
            """{"ok":false,"error":"Invalid MCP request: ${e.message}"}"""
        }
    }
}
