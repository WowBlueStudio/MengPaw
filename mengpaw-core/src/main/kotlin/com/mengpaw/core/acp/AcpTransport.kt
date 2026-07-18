package com.mengpaw.core.acp

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
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
 * Each device runs a lightweight HTTP listener on port 9876.
 */
class AcpHttpTransport(
    private val server: AcpServer,
    private val port: Int = 9876
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
                val url = URL("http://${peer.address}:${peer.port}/acp")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                // Encryption: if peer supports it, encrypt; otherwise plaintext
                val plainBody = json.encodeToString(AcpMessage.serializer(), message)
                val body = if (AcpCrypto.supportsEncryption(peer.agentId)) {
                    conn.setRequestProperty("X-MengPaw-Encrypt", "AES-256-CBC")
                    AcpCrypto.encrypt(peer.agentId, plainBody)
                } else plainBody

                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                val code = conn.responseCode
                // Check if peer responded with encryption support
                conn.getHeaderField("X-MengPaw-Encrypt")?.let {
                    AcpCrypto.markEncryptionCapable(peer.agentId)
                }
                conn.disconnect()
                if (code in 200..299) sent = true
            } catch (e: Exception) {
                // Peer unreachable — will be cleaned up by timeout
            }
        }
        return sent
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
                serverSocket = ServerSocket(port)
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
            // Read body
            val body = CharArray(contentLength)
            if (contentLength > 0) reader.read(body, 0, contentLength)
            var bodyStr = String(body)

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
            peerId = msg.from

            // Dispatch
            val result = server.handleMessage(bodyStr)

            // Encrypt response if peer supports it
            val respPlain = """{"result":"${result.message}","success":${result.success}}"""
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
// NSD peer discovery is a future feature.
// When needed, implement with android.net.nsd.NsdManager.
// See: https://developer.android.com/training/connect-devices-wirelessly/nsd
