// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.openclaw

import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import com.mengpaw.kernel.spi.FrameworkAdapter
import com.mengpaw.kernel.spi.FrameworkAdapterRegistry
import com.mengpaw.kernel.spi.FrameworkTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenClaw 连接器插件 (外部分发, 不内置) — 经 WebSocket (18789) 对接 OpenClaw 框架。
 *
 * 实现内核 FrameworkAdapter SPI; onInstall 时注册进 FrameworkAdapterRegistry,
 * plugin-framework 的 `framework.connect/call` 自动分派。
 * 协议: MCP JSON-RPC 帧 (tools/list / tools/call) — OpenClaw mcp-serve 兼容。
 */
class OpenClawConnectorPlugin : Plugin, FrameworkAdapter {

    override val metadata = PluginMetadata(
        id = "connector-openclaw-plugin",
        name = "OpenClaw 连接器",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "OpenClaw 框架连接器 — WebSocket (18789) 对接, 经 framework.connect/call 调用",
        minCoreVersion = "0.20.0",
        commands = emptyList()
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = emptyMap()
    override val uiButtons: List<com.mengpaw.kernel.plugin.PluginUiButton> = emptyList()

    // ── FrameworkAdapter ────────────────────────────────────────────────

    override val frameworkName: String = "openclaw"

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var target: FrameworkTarget? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val idCounter = AtomicInteger(1)

    override suspend fun connect(target: FrameworkTarget): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wsUrl = "ws://${target.address}:${target.port}"
            val request = Request.Builder().url(wsUrl).build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val id = JSONObject(text).optInt("id", -1)
                        if (id >= 0) pending.remove(id)?.complete(text)
                    } catch (_: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    pending.values.forEach { it.completeExceptionally(t) }
                    pending.clear()
                }
            })
            this@OpenClawConnectorPlugin.webSocket = ws
            this@OpenClawConnectorPlugin.target = target
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        try { webSocket?.close(1000, "mengpaw-disconnect") } catch (_: Exception) {}
        webSocket = null
        target = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException("disconnected")) }
        pending.clear()
    }

    override suspend fun callTool(tool: String, args: Map<String, String>): Result<String> {
        val ws = webSocket ?: return Result.failure(IllegalStateException("未连接 — 先执行 framework.connect"))
        val id = idCounter.getAndIncrement()
        val jsonArgs = JSONObject(args).toString()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "tools/call")
            .put("id", id)
            .put("params", JSONObject().put("name", tool).put("arguments", JSONObject(args)))
            .toString()
        return try {
            val future = CompletableFuture<String>()
            pending[id] = future
            if (!ws.send(payload)) {
                pending.remove(id)
                return Result.failure(IllegalStateException("WebSocket 发送失败"))
            }
            // 等响应 (最多 30s)
            val resp = future.get(30, TimeUnit.SECONDS)
            Result.success(resp)
        } catch (e: Exception) {
            pending.remove(id)
            Result.failure(e)
        }
    }

    override fun isOnline(): Boolean = webSocket != null

    // ── Plugin lifecycle ────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        FrameworkAdapterRegistry.register(this)
        ctx.log("OpenClaw 连接器已注册 — framework.connect 即可对接")
    }

    override suspend fun onUninstall() {
        disconnect()
        FrameworkAdapterRegistry.unregister(frameworkName)
    }

    override suspend fun onUpgrade(newVersion: String) {}
}
