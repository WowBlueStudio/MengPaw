// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.browsermcp

import android.webkit.WebView
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.mcp.McpTool
import com.mengpaw.kernel.mcp.McpToolProvider
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exposes browser capabilities as MCP (Model Context Protocol) tools.
 *
 * 设备内通道: Shell 进程 ↔ 浏览器进程经 HTTP 桥 (127.0.0.1:9880, Ports.BROWSER_MCP)。
 * 浏览器侧 McpHttpServer 处理 /health 与 /mcp; 本插件是 HTTP client。
 * (废弃旧反射静态字段绑定 — 跨进程字段赋值互不可见, 通道从未真正工作)
 *
 * ## Design reference (MIT-licensed):
 * native-devtools-mcp: MCP tool provider pattern for browser automation
 */
class BrowserMcpPlugin : Plugin, McpToolProvider {
    override val metadata = PluginMetadata(
        id = "browser-mcp-plugin",
        name = "浏览器 MCP",
        version = "0.20.2",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "将 MP 浏览器能力暴露为 MCP 工具：导航/截图/点击/输入/提取/执行脚本 (设备内 HTTP 桥)",
        permissions = emptyList(),
        minCoreVersion = "0.2.3",
        commands = listOf("browser.mcp.tools", "browser.mcp.status", "browser.mcp.invoke")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "mcp.tools" to ::listTools,
        "mcp.status" to ::status,
        "mcp.invoke" to ::invokeTool,
    )

    companion object {
        /** 设备内 MCP 桥地址 (浏览器侧 McpHttpServer 监听 127.0.0.1). */
        @Volatile
        var serverUrl: String = "http://127.0.0.1:${com.mengpaw.kernel.ports.Ports.BROWSER_MCP}"

        // ── 兼容保留 (旧反射绑定机制已废弃, 字段不再被赋值, 仅作 fallback) ──
        @JvmField
        var webViewProvider: (() -> WebView?)? = null

        @JvmField
        var toolExecutor: ((String, Map<String, String>) -> String)? = null
    }

    // ── HTTP client (复用 McpClient.callHttp 的 HttpURLConnection 先例, 零新依赖) ──

    private fun httpGet(url: String, timeoutMs: Int = 2000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
    } catch (_: Exception) { null }

    private fun httpPost(url: String, body: String, timeoutMs: Int = 30_000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
    } catch (_: Exception) { null }

    // ── McpToolProvider ─────────────────────────────────────────────────

    /** tools/call 支持: 经设备内 HTTP 桥调浏览器 (供内核 McpServer 聚合时使用)。 */
    override fun callTool(name: String, arguments: Map<String, String>): Result<String> = try {
        val body = JSONObject().put("tool", name).put("args", JSONObject(arguments)).toString()
        val result = httpPost("$serverUrl/mcp", body)
            ?: return Result.failure(IllegalStateException("浏览器 MCP 服务未启动 — 打开 MP 浏览器"))
        Result.success(result)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun getTools(): List<McpTool> = listOf(
        McpTool("browser_navigate", "Navigate to a URL",
            mapOf("url" to mapOf("type" to "string", "description" to "The URL to navigate to"))),
        McpTool("browser_screenshot", "Capture a screenshot of the current page",
            mapOf("fullPage" to mapOf("type" to "boolean", "description" to "Capture full page or viewport only"))),
        McpTool("browser_click", "Click an element by CSS selector",
            mapOf("selector" to mapOf("type" to "string", "description" to "CSS selector of the element to click"))),
        McpTool("browser_type", "Type text into an input element",
            mapOf("selector" to mapOf("type" to "string"), "text" to mapOf("type" to "string"))),
        McpTool("browser_extract", "Extract structured page content (title, links, forms, text)",
            emptyMap()),
        McpTool("browser_eval", "Execute JavaScript in the page",
            mapOf("script" to mapOf("type" to "string", "description" to "JavaScript code to execute"))),
    )

    // ── CLI ─────────────────────────────────────────────────────────────

    private suspend fun listTools(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val tools = getTools()
        val output = buildString {
            appendLine("## MCP 浏览器工具 (${tools.size})")
            appendLine()
            tools.forEach { tool ->
                appendLine("### ${tool.name}")
                appendLine("- ${tool.description}")
                if (tool.inputSchema.isNotEmpty()) {
                    appendLine("- 参数:")
                    tool.inputSchema.forEach { (k, v) ->
                        val schema = v as? Map<*, *>
                        appendLine("  - `$k`: ${schema?.get("description") ?: schema?.get("type") ?: ""}")
                    }
                }
                appendLine()
            }
            appendLine("---")
            appendLine("使用 `browser.mcp.invoke <工具名> <JSON参数>` 直接调用工具。")
        }
        return ExecutionResult.ok(output)
    }

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val health = withContext(Dispatchers.IO) { httpGet("$serverUrl/health", 2000) }
        return if (health != null) {
            ExecutionResult.ok(
                "浏览器 MCP 服务: 在线 ($serverUrl)\n" +
                "已注册 ${getTools().size} 个工具。\n" +
                "使用 `browser.mcp.invoke <工具名> <JSON参数>` 调用。"
            )
        } else {
            ExecutionResult.ok(
                "浏览器 MCP 服务: 离线 — 打开 MP 浏览器即自动启动 ($serverUrl)\n" +
                "已注册 ${getTools().size} 个工具 (待浏览器在线后可用)。"
            )
        }
    }

    /** Invoke a named MCP tool with JSON arguments and return the result. */
    private suspend fun invokeTool(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.mcp.invoke <toolName> [jsonArgs]\n工具列表见 browser.mcp.tools",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val toolName = args[0]
        val jsonArgs = if (args.size > 1) {
            try {
                val json = JSONObject(args.drop(1).joinToString(" "))
                val map = mutableMapOf<String, String>()
                for (key in json.keys()) {
                    map[key] = json.optString(key, "")
                }
                map
            } catch (_: Exception) {
                emptyMap()
            }
        } else emptyMap()

        // 主通道: 设备内 HTTP 桥 → 浏览器 McpHttpServer → WebView
        val body = JSONObject()
            .put("tool", toolName)
            .put("args", JSONObject(jsonArgs))
            .toString()
        val result = withContext(Dispatchers.IO) { httpPost("$serverUrl/mcp", body) }
        return if (result != null) {
            ExecutionResult.ok(result)
        } else {
            ExecutionResult.ok(
                """{"ok":false,"error":"浏览器 MCP 服务未启动 — 打开 MP 浏览器即自动启动 ($serverUrl)"}"""
            )
        }
    }
}
