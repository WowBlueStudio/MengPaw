// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.ports.Ports
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.*

/**
 * ACP network transport — enables device-to-device Agent communication.
 *
 * ## Architecture
 * ```
 * Phone A                      Phone B (tablet)
 * ┌──────────┐    LAN WiFi     ┌──────────┐
 * │ AcpServer│ ←─── HTTP ────→ │ AcpServer│
 * │ +NSD     │                 │ +NSD     │
 * └──────────┘                 └──────────┘
 * ```
 *
 * ## Discovery
 * Uses Android NSD (Network Service Discovery) to find peers on LAN.
 * Registers as "_mengpaw-acp._tcp" service.
 *
 * ## Message Exchange
 * Simple HTTP POST to peer's IP:port with JSON body.
 * Each device runs a lightweight HTTP listener on the ACP port (Ports.ACP).
 */
class AcpHttpTransport(
    private val server: AcpServer,
    private val port: Int = Ports.ACP
) : AcpTransport {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun isConnected(): Boolean = running

    override suspend fun send(message: AcpMessage): Boolean {
        // Send to all known peers
        val peers = server.getPeers()
        if (peers.isEmpty()) return false

        var sent = false
        peers.forEach { peer ->
            try {
                postMessage(message, peer.address, peer.port, peer.agentId)
                sent = true
            } catch (e: Exception) {
                // Peer unreachable — will be cleaned up by timeout
            }
        }
        return sent
    }

    /**
     * 请求-响应发送 (v0.22.0): 发给指定 peer, 读取并解析 HTTP 响应体。
     * 修复历史缺陷 — 原 send() 只读 responseCode 丢弃响应体, 导致账本条目
     * 永远回不到请求方, 孪生同步端到端不可达。
     */
    override suspend fun sendForResult(
        message: AcpMessage,
        toPeerId: String,
        timeoutMs: Long
    ): AcpResult? {
        val peer = server.getPeers().firstOrNull { it.agentId == toPeerId }
            ?: server.getPeers().firstOrNull { it.agentId.startsWith(toPeerId) }
            ?: return null
        return try {
            val (respBody, encrypted) = withTimeoutOrNull(timeoutMs) {
                postMessage(message, peer.address, peer.port, peer.agentId, readTimeoutMs = 15_000)
            } ?: return AcpResult(false, "request_timeout")
            if (respBody.isNullOrBlank()) return null
            val plain = if (encrypted) AcpCrypto.decrypt(peer.agentId, respBody) else respBody
            parseResponse(plain)
        } catch (e: Exception) {
            AcpResult(false, "transport_error: ${e.message}")
        }
    }

    /** POST 一条消息, 返回响应体文本 + 是否加密。 */
    private fun postMessage(
        message: AcpMessage, address: String, port: Int, peerId: String,
        readTimeoutMs: Int = 3000
    ): Pair<String?, Boolean> {
        val url = URL("http://$address:$port/acp")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 3000
        conn.readTimeout = readTimeoutMs
        try {
            // Encryption: if peer supports it, encrypt; otherwise plaintext
            val plainBody = json.encodeToString(AcpMessage.serializer(), message)
            val body = if (AcpCrypto.supportsEncryption(peerId)) {
                conn.setRequestProperty("X-MengPaw-Encrypt", "AES-256-CBC")
                AcpCrypto.encrypt(peerId, plainBody)
            } else plainBody

            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            // Check if peer responded with encryption support
            conn.getHeaderField("X-MengPaw-Encrypt")?.let {
                AcpCrypto.markEncryptionCapable(peerId)
            }
            if (code !in 200..299) return null to false
            // FIX (v0.22.0): 读取响应体 — 对端处理结果在 JSON body 的 data 字段
            val respBody = conn.inputStream.bufferedReader().use { it.readText() }
            return respBody to (conn.getHeaderField("X-MengPaw-Encrypt") == "AES-256-CBC")
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 {success, result, data} JSON 响应。 */
    private fun parseResponse(body: String): AcpResult? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val data = obj["data"]?.jsonPrimitive?.content ?: ""
            AcpResult(
                success = obj["success"]?.jsonPrimitive?.boolean ?: false,
                message = obj["result"]?.jsonPrimitive?.content ?: "",
                data = data
            )
        } catch (e: Exception) {
            AcpResult(false, "invalid_response")
        }
    }

    override suspend fun receive(): AcpMessage? {
        // HTTP listener runs in background — messages arrive via handleIncoming()
        return null
    }

    override fun close() {
        running = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    /** Start HTTP listener for incoming ACP messages. */
    fun startListener() {
        if (running) return
        running = true
        scope.launch {
            try {
                // 故意监听所有接口 (0.0.0.0): ACP 是设备间通道, 对端设备必须能直连本机。
                // 安全机制: AcpServer.bindPeerIp 将 peerId 绑定到来源 socket IP (防 msg.from 伪造),
                // 敏感消息类型 (会话/工作区/REVOKE/MCP) 额外要求 IP 绑定匹配;
                // 设备级认证依赖 AcpServer sharedSecret (pairing 时派生), 未设置时启动即告警。
                // 对比: 本机专用端口 (BROWSER_MCP 9880 / MCP_LOCAL 9881) 显式绑定 127.0.0.1。
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                while (running && isActive) {
                    try {
                        val client = serverSocket?.accept() ?: continue
                        launch(Dispatchers.IO) {
                            handleHttpRequest(client)
                        }
                    } catch (e: Exception) {
                        if (running) delay(100) // retry
                    }
                }
            } catch (e: Exception) {
                // Port in use or network unavailable
            }
        }
    }

    private suspend fun handleHttpRequest(socket: Socket) {
        // VULN-FIX: Bind peer identity to socket address, not spoofable msg.from
        val remoteAddr = (socket.inetAddress?.hostAddress ?: "unknown")
        var peerId = remoteAddr // Default to IP-based identity
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            var contentLength = 0
            while (line != null && line.isNotEmpty()) {
                headers[line.substringBefore(":").trim().lowercase()] = line.substringAfter(":").trim()
                if (line.startsWith("Content-Length:", ignoreCase = true))
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                line = reader.readLine()
            }
            // v0.35.5 修复: body 必须循环读满 — 单次 read 不保证返回全部字节
            // (WiFi 分片/缓冲), 真实配对请求 ~250B 常被截断 → JSON 解析 400
            // "Invalid ACP message", 用户点击"添加"提示发送失败
            var bodyStr = readFully(reader, contentLength)

            // Decrypt if peer sent encrypted
            val isEncrypted = headers["x-mengpaw-encrypt"] == "AES-256-CBC"
            val fromHeader = headers["x-mengpaw-from"] ?: ""

            // Parse ACP message to get peer ID
            val msg = try {
                if (isEncrypted && fromHeader.isNotBlank()) {
                    bodyStr = AcpCrypto.decrypt(fromHeader, bodyStr)
                }
                json.decodeFromString(AcpMessage.serializer(), bodyStr)
            } catch (e: Exception) {
                val err = """{"result":"Invalid ACP message","success":false}"""
                writer.write("HTTP/1.1 400 Bad Request\r\nContent-Length: ${err.length}\r\n\r\n$err")
                writer.flush()
                return
            }
            // SECURITY (P0 fix): peer identity MUST be bound to the socket address —
            // msg.from is spoofable plaintext, so an attacker on the LAN could claim
            // any paired peer's agentId and pass isTrusted(). All messages establish
            // an IP binding; sensitive types additionally require the source IP to be
            // one this peerId has previously communicated from.
            server.bindPeerIp(msg.from, remoteAddr)
            if (msg.type in SENSITIVE_ACP_TYPES && !server.isPeerFromBoundIp(msg.from, remoteAddr)) {
                val err = """{"result":"auth_required: peer identity not bound to source address","success":false}"""
                writer.write("HTTP/1.1 403 Forbidden\r\nContent-Length: ${err.length}\r\n\r\n$err")
                writer.flush()
                return
            }
            peerId = msg.from

            // Dispatch
            val result = server.handleMessage(bodyStr)

            // Encrypt response if peer supports it
            val dataJson = if (result.data.isNotBlank()) {
                val escaped = result.data.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                ""","data":"$escaped""""
            } else ""
            val respPlain = """{"result":"${result.message}","success":${result.success}$dataJson}"""
            val resp = if (isEncrypted && peerId.isNotBlank()) {
                AcpCrypto.encrypt(peerId, respPlain)
            } else respPlain

            val headerLine = if (isEncrypted) "X-MengPaw-Encrypt: AES-256-CBC\r\n" else ""
            writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n$headerLine\r\nContent-Length: ${resp.length}\r\n\r\n$resp")
            writer.flush()
        } catch (e: Exception) {
            // Invalid message — ignore
            try {
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("HTTP/1.1 400 Bad Request\r\n\r\n")
                writer.flush()
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}

/**
 * 从 [reader] 循环读取 [length] 字节并转字符串 (v0.35.5) —
 * 单次 `read()` 不保证返回全部字节 (网络分片/缓冲), 必须循环至读满。
 * 连接提前关闭时返回已读部分; 供 handleHttpRequest 解析 ACP 消息 body。
 */
internal fun readFully(reader: java.io.BufferedReader, length: Int): String {
    val body = CharArray(length)
    var read = 0
    while (read < length) {
        val n = reader.read(body, read, length - read)
        if (n < 0) break
        read += n
    }
    return String(body, 0, read)
}
// NSD peer discovery is a future feature.
// When needed, implement with android.net.nsd.NsdManager.
// See: https://developer.android.com/training/connect-devices-wirelessly/nsd

/** ACP 类型中需要 peer 身份可验证的消息 (工作区同步/会话/解绑/MCP — 均触及敏感数据或命令执行)。
 * 与 AcpServer.processMessage 中的 isTrusted 分支一一对应, 此处补 IP 绑定校验。 */
private val SENSITIVE_ACP_TYPES = setOf(
    "WS_MANIFEST", "WS_PULL", "REVOKE",
    "SESSION_HEAD", "SESSION_PULL", "SESSION_DELTA", "SESSION_ACK",
    "MCP_REQUEST"
)
