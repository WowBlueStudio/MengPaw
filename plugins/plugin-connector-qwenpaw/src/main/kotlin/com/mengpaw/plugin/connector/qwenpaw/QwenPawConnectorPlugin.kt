// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.qwenpaw

import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import com.mengpaw.kernel.spi.FrameworkAdapter
import com.mengpaw.kernel.spi.FrameworkAdapterRegistry
import com.mengpaw.kernel.spi.FrameworkTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * QwenPaw 连接器插件 (外部分发, 不内置) — 经 REST (8080) 对接 QwenPaw/Coze 框架。
 *
 * 实现内核 FrameworkAdapter SPI; onInstall 时注册进 FrameworkAdapterRegistry,
 * plugin-framework 的 `framework.connect/call` 自动分派。
 * 协议: HTTP POST MCP JSON-RPC (复用 McpClient.callHttp 先例, 零新依赖)。
 */
class QwenPawConnectorPlugin : Plugin, FrameworkAdapter {

    override val metadata = PluginMetadata(
        id = "connector-qwenpaw-plugin",
        name = "QwenPaw 连接器",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "QwenPaw/Coze 框架连接器 — REST (8080) 对接, 经 framework.connect/call 调用",
        minCoreVersion = "0.20.0",
        commands = emptyList()
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = emptyMap()
    override val uiButtons: List<com.mengpaw.kernel.plugin.PluginUiButton> = emptyList()

    // ── FrameworkAdapter ────────────────────────────────────────────────

    override val frameworkName: String = "qwenpaw"

    @Volatile private var endpoint: String? = null

    override suspend fun connect(target: FrameworkTarget): Result<Unit> {
        // REST 无长连接 — 存端点即"在线" (callTool 时真实探测)
        endpoint = "http://${target.address}:${target.port}/mcp"
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        endpoint = null
    }

    override suspend fun callTool(tool: String, args: Map<String, String>): Result<String> = withContext(Dispatchers.IO) {
        val base = endpoint ?: return@withContext Result.failure(IllegalStateException("未连接 — 先执行 framework.connect"))
        try {
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "tools/call")
                .put("id", 1)
                .put("params", JSONObject().put("name", tool).put("arguments", JSONObject(args)))
                .toString()
            val conn = URL(base).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) Result.success(body) else Result.failure(IllegalStateException("HTTP $code: ${body.take(200)}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun isOnline(): Boolean = endpoint != null

    // ── Plugin lifecycle ────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        FrameworkAdapterRegistry.register(this)
        ctx.log("QwenPaw 连接器已注册 — framework.connect 即可对接")
    }

    override suspend fun onUninstall() {
        disconnect()
        FrameworkAdapterRegistry.unregister(frameworkName)
    }

    override suspend fun onUpgrade(newVersion: String) {}
}
