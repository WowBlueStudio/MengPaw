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
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * Exposes browser capabilities as MCP (Model Context Protocol) tools.
 *
 * External MCP clients can discover and invoke browser tools:
 * - tools/list → sees browser_navigate, browser_screenshot, browser_click, etc.
 * - tools/call → invokes the tool with parameters
 *
 * ## Design reference (MIT-licensed):
 * native-devtools-mcp: MCP tool provider pattern for browser automation
 */
class BrowserMcpPlugin : Plugin, McpToolProvider {
    override val metadata = PluginMetadata(
        id = "browser-mcp-plugin",
        name = "浏览器 MCP",
        version = "0.2.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "将 MP 浏览器能力暴露为 MCP 工具：导航/截图/点击/输入/提取/执行脚本",
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
        /** WebView provider set by BrowserActivity on initialization. */
        @JvmField
        var webViewProvider: (() -> WebView?)? = null

        /**
         * Tool executor delegate — set by BrowserActivity to bridge plugin ↔ browser.
         * Accepts (toolName, args) and returns JSON result string.
         */
        @JvmField
        var toolExecutor: ((String, Map<String, String>) -> String)? = null

        /** Execute a named MCP tool with JSON args, returning JSON result. */
        fun executeTool(toolName: String, args: Map<String, String>): String {
            return toolExecutor?.invoke(toolName, args)
                ?: """{"ok":false,"error":"Tool executor not available — activate in MP Browser"}"""
        }
    }

    // ── McpToolProvider ─────────────────────────────────────────────────

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
        val available = webViewProvider?.invoke() != null
        val statusText = if (available) "就绪" else "WebView 未绑定 — 请在 MP 浏览器中激活此插件"
        return ExecutionResult.ok("浏览器 MCP 服务状态: $statusText\n已注册 ${getTools().size} 个工具。")
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
                val json = org.json.JSONObject(args.drop(1).joinToString(" "))
                val map = mutableMapOf<String, String>()
                for (key in json.keys()) {
                    map[key] = json.optString(key, "")
                }
                map
            } catch (_: Exception) {
                emptyMap()
            }
        } else emptyMap()

        val result = executeTool(toolName, jsonArgs)
        return ExecutionResult.ok(result)
    }
}
