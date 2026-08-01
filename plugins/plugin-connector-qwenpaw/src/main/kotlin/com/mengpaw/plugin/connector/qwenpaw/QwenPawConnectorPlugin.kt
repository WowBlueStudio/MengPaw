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
import com.mengpaw.plugin.connector.common.ConnectorCommand
import com.mengpaw.plugin.connector.common.ConnectorConfigStore
import com.mengpaw.plugin.connector.common.InteractiveChannel
import com.mengpaw.plugin.connector.common.SshTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * QwenPaw 通讯连接器 (外部分发, 不内置) — 对接 QwenPaw/Coze (agentscope-ai/QwenPaw, Apache-2.0)。
 *
 * v0.2.0 重写为真实协议 (v0.1.0 为参考实现, 端点不存在):
 * - REST 通道 (默认): `qwenpaw app` HTTP API — POST /api/console/chat (默认端口 8088,
 *   头 X-Agent-Id, 体 {input:[{role,content:[{type:"text",text}]}], session_id, user_id, channel},
 *   SSE 流响应 status: created/in_progress/completed/failed)
 * - SSH ACP 通道 (实验性): SSH 执行 `qwenpaw acp` — stdio JSON-RPC
 *   (initialize → new_session → prompt; agent_message_chunk 经 session_update 通知累积)
 * 通道经 connector-qwenpaw.config --channel rest|ssh-acp 切换。
 *
 * 工具映射 (framework.call <peer> <tool>):
 *   chat        {text, agentId?, sessionId?} → REST (仅 rest 通道)
 *   acp-prompt  {text, sessionId?}           → SSH ACP (仅 ssh-acp 通道)
 *
 * 配置: connector-qwenpaw.config (凭据/通道) / connector-qwenpaw.info (状态)。
 */
class QwenPawConnectorPlugin : Plugin, FrameworkAdapter {

    override val metadata = PluginMetadata(
        id = "connector-qwenpaw-plugin",
        name = "QwenPaw 通讯",
        version = "0.2.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "QwenPaw/Coze 通讯连接器 — REST (8088) 直连 + SSH ACP 双通道, 经 framework.connect/call 委派任务",
        minCoreVersion = "0.20.0",
        commands = listOf(
            "connector-qwenpaw.config",
            "connector-qwenpaw.info"
        )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "config" to ConnectorCommand.configHandler(PLUGIN_ID),
        "info" to ConnectorCommand.infoHandler(PLUGIN_ID) {
            val cfg = ConnectorConfigStore.read(PLUGIN_ID)
            when {
                cfg.channel == "rest" && endpoint != null -> "在线 (REST: $endpoint)"
                cfg.channel == "ssh-acp" && acp != null -> "在线 (SSH ACP: $connectedHost)"
                else -> "离线"
            }
        }
    )
    override val uiButtons: List<com.mengpaw.kernel.plugin.PluginUiButton> = emptyList()

    // ── FrameworkAdapter ────────────────────────────────────────────────

    override val frameworkName: String = "qwenpaw"
    override val toolsDescription: String =
        "chat {text,agentId?,sessionId?} — REST 对话 (SSE 流, 阻塞最长 2 分钟); acp-prompt {text} — SSH ACP 对话 (实验性)"

    /** REST 通道端点 (http://host:port, 无长连接, callTool 时真实探测)。 */
    @Volatile private var endpoint: String? = null
    /** SSH ACP 通道 (实验性)。 */
    @Volatile private var transport: SshTransport? = null
    @Volatile private var acp: AcpOverSsh? = null
    @Volatile private var connectedHost: String = ""

    override suspend fun connect(target: FrameworkTarget): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = ConnectorConfigStore.read(PLUGIN_ID)
        when (cfg.channel) {
            "rest" -> {
                endpoint = "http://${target.address}:${target.port}"
                Result.success(Unit)
            }
            "ssh-acp" -> connectSshAcp(target, cfg)
            else -> Result.failure(IllegalStateException("未知通道: ${cfg.channel} (可用: rest / ssh-acp)"))
        }
    }

    private fun connectSshAcp(target: FrameworkTarget, cfg: ConnectorConfigStore.ConnectorConfig): Result<Unit> {
        if (cfg.user.isBlank()) {
            return Result.failure(IllegalStateException(
                "未配置 SSH 凭据 — 先执行 connector-qwenpaw.config --user <用户名> [--password <密码> | --key-path <PEM路径>]"
            ))
        }
        val host = target.address
        val port = if (cfg.sshPort in 1..65535) cfg.sshPort else 22
        val t = SshTransport(host, port, cfg.user, cfg.password, cfg.keyPath, cfg.keyPassphrase)
        val connected = t.connect()
        if (connected.isFailure) {
            t.disconnect()
            return Result.failure(IllegalStateException(
                "SSH 连接失败 (${target.address}:$port): ${connected.exceptionOrNull()?.message} — 检查 PC 端 OpenSSH Server 与凭据"
            ))
        }
        val cli = cfg.cliPath?.takeIf { it.isNotBlank() } ?: "qwenpaw"
        val chResult = t.openInteractive("\"$cli\" acp")
        if (chResult.isFailure) {
            t.disconnect()
            return Result.failure(IllegalStateException(
                "启动 qwenpaw acp 失败: ${chResult.exceptionOrNull()?.message} — 检查 PC 端 QwenPaw 安装或 --cli-path"
            ))
        }
        val session = AcpOverSsh(chResult.getOrThrow())
        val init = session.call("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "MengPaw").put("version", "0.2.0")).put("clientCapabilities", JSONObject()))
        if (init.isFailure) {
            session.close()
            t.disconnect()
            return Result.failure(IllegalStateException("QwenPaw ACP initialize 失败: ${init.exceptionOrNull()?.message}"))
        }
        val newSession = session.call("new_session", JSONObject())
        if (newSession.isFailure) {
            session.close()
            t.disconnect()
            return Result.failure(IllegalStateException("QwenPaw ACP new_session 失败: ${newSession.exceptionOrNull()?.message}"))
        }
        // 提取 session_id (ACP: result.session_id; 部分实现直接放顶层)
        val newSessionObj = newSession.getOrThrow()
        val sid = newSessionObj.optJSONObject("result")?.optString("session_id")?.takeIf { it.isNotBlank() }
            ?: newSessionObj.optString("session_id").takeIf { it.isNotBlank() }
        if (sid.isNullOrBlank()) {
            session.close()
            t.disconnect()
            return Result.failure(IllegalStateException("new_session 响应缺少 session_id: $newSessionObj"))
        }
        session.setSessionId(sid)
        this@QwenPawConnectorPlugin.transport = t
        this@QwenPawConnectorPlugin.acp = session
        this@QwenPawConnectorPlugin.connectedHost = "${target.address}:$port"
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        try { acp?.close() } catch (_: Exception) {}
        transport?.disconnect()
        acp = null
        transport = null
        endpoint = null
        connectedHost = ""
    }

    override suspend fun callTool(tool: String, args: Map<String, String>): Result<String> = withContext(Dispatchers.IO) {
        val cfg = ConnectorConfigStore.read(PLUGIN_ID)
        when (tool) {
            "chat" -> {
                if (cfg.channel != "rest" || endpoint == null) {
                    return@withContext Result.failure(IllegalStateException(
                        "chat 需要 REST 通道 — connector-qwenpaw.config --channel rest 后 framework.connect"
                    ))
                }
                restChat(args)
            }
            "acp-prompt" -> {
                val a = acp ?: return@withContext Result.failure(IllegalStateException(
                    "acp-prompt 需要 SSH ACP 通道 — connector-qwenpaw.config --channel ssh-acp 后 framework.connect"
                ))
                val text = args["text"] ?: return@withContext Result.failure(IllegalStateException("acp-prompt 需要 text 参数"))
                a.prompt(text)
            }
            else -> Result.failure(IllegalStateException("未知工具: $tool (可用: chat / acp-prompt)"))
        }
    }

    /** REST chat — POST /api/console/chat, SSE 流解析到 completed 提取 output 文本。 */
    private fun restChat(args: Map<String, String>): Result<String> {
        return try {
            val base = endpoint ?: return Result.failure(IllegalStateException("未连接 — 先执行 framework.connect"))
            val agentId = args["agentId"]?.takeIf { it.isNotBlank() } ?: "default"
            val text = args["text"] ?: return Result.failure(IllegalStateException("chat 需要 text 参数"))
            val sessionId = args["sessionId"]?.takeIf { it.isNotBlank() } ?: "mengpaw-${System.currentTimeMillis()}"
            val body = JSONObject()
                .put("input", JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content", JSONArray().put(JSONObject().put("type", "text").put("text", text))
                    )
                ))
                .put("session_id", sessionId)
                .put("user_id", "mengpaw")
                .put("channel", "console")
            val conn = URL("$base/api/console/chat").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Agent-Id", agentId)
                // qwenpaw app 开启认证时需 Bearer token (connector-qwenpaw.config --token)
                val token = ConnectorConfigStore.read(PLUGIN_ID).token?.takeIf { it.isNotBlank() }
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText() ?: ""
                    return Result.failure(IllegalStateException("HTTP $code: ${err.take(300)}"))
                }
                val sb = StringBuilder()
                val reader = conn.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null) {
                    val payload = line.trim()
                    if (payload.startsWith("data:")) {
                        val data = payload.removePrefix("data:").trim()
                        if (data.isNotBlank()) {
                            try {
                                val evt = JSONObject(data)
                                when (evt.optString("status")) {
                                    "completed" -> {
                                        val output = evt.optJSONArray("output")
                                        if (output != null && output.length() > 0) {
                                            val content = output.getJSONObject(0).optJSONArray("content")
                                            if (content != null) {
                                                for (i in 0 until content.length()) {
                                                    val part = content.getJSONObject(i)
                                                    if (part.optString("type") == "text") sb.append(part.optString("text"))
                                                }
                                            }
                                        }
                                        return Result.success(sb.toString().trim())
                                    }
                                    "failed" -> return Result.failure(
                                        IllegalStateException("QwenPaw 调用失败: ${evt.optString("error").take(300)}")
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    line = reader.readLine()
                }
                Result.failure(IllegalStateException("响应流意外结束 (未收到 completed 事件)"))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun isOnline(): Boolean {
        val cfg = ConnectorConfigStore.read(PLUGIN_ID)
        return when (cfg.channel) {
            "rest" -> endpoint != null
            else -> acp != null && transport?.isConnected() == true
        }
    }

    // ── Plugin lifecycle ────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        FrameworkAdapterRegistry.register(this)
        ctx.log("QwenPaw 通讯连接器已注册 (v0.2.0 真实协议) — connector-qwenpaw.config 配置后 framework.add/connect 对接")
    }

    override suspend fun onUninstall() {
        disconnect()
        FrameworkAdapterRegistry.unregister(frameworkName)
    }

    override suspend fun onUpgrade(newVersion: String) {}

    companion object {
        const val PLUGIN_ID = "connector-qwenpaw-plugin"
    }

    /**
     * QwenPaw ACP stdio JSON-RPC 客户端 (实验性) — newline-delimited 请求/响应,
     * session_update 通知中的 agent_message_chunk 文本累积到 [textBuffer]。
     * 协议基于 ACP-1.0 (Agent Client Protocol, BigCode) 的 QwenPaw 实现。
     */
    private class AcpOverSsh(private val ch: InteractiveChannel) {
        private val idCounter = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
        private val textBuffer = StringBuilder()

        init {
            val readerThread = Thread {
                try {
                    while (true) {
                        val line = ch.readLine(60_000) ?: break
                        val j = try { JSONObject(line) } catch (_: Exception) { continue }
                        if (j.has("id")) {
                            pending.remove(j.optInt("id"))?.complete(j)
                        } else if (j.optString("method") == "session_update") {
                            extractText(j)
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.isDaemon = true
            readerThread.start()
        }

        private fun extractText(evt: JSONObject) {
            val event = evt.optJSONObject("params")?.optJSONObject("event") ?: return
            if (event.optString("type") == "agent_message_chunk") {
                val content = event.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val part = content.getJSONObject(i)
                    if (part.optString("type") == "text") textBuffer.append(part.optString("text"))
                }
            }
        }

        /** JSON-RPC 请求 — 按 id 关联响应 (嵌套类, 供外部类调用故为 internal)。 */
        internal fun call(method: String, params: JSONObject, timeoutMs: Long = 60_000): Result<JSONObject> {
            val id = idCounter.getAndIncrement()
            val req = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            val future = CompletableFuture<JSONObject>()
            pending[id] = future
            return try {
                ch.writeLine(req.toString())
                val resp = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                val err = resp.optJSONObject("error")
                if (err != null) Result.failure(IllegalStateException(err.optString("message", "ACP 错误")))
                else Result.success(resp)
            } catch (e: Exception) {
                pending.remove(id)
                Result.failure(e)
            }
        }

        /** 发送提示词并收集 agent_message_chunk 文本 (最多 5 分钟)。 */
        fun prompt(text: String): Result<String> {
            val sessionId = sessionIdHolder
            if (sessionId == null) return Result.failure(IllegalStateException("ACP 会话未建立 (new_session 失败)"))
            textBuffer.setLength(0)
            val params = JSONObject().put("session_id", sessionId)
                .put("prompt", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            val result = call("prompt", params, 300_000)
            return result.map { textBuffer.toString().trim() }
        }

        private var sessionIdHolder: String? = null

        fun setSessionId(id: String?) { sessionIdHolder = id }

        fun close() {
            // 未决请求立即失败 — 避免挂到超时 (60s)
            pending.values.forEach { it.completeExceptionally(IllegalStateException("ACP 通道已关闭")) }
            pending.clear()
            ch.close()
        }
    }
}
