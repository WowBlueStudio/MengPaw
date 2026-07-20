// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.browsermcp

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.mcp.McpTool
import com.mengpaw.core.mcp.McpToolProvider
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginContext
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType

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
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "将 MP 浏览器能力暴露为 MCP 工具：导航/截图/点击/输入/提取",
        permissions = emptyList(),
        minCoreVersion = "0.2.3",
        commands = listOf("browser.mcp.tools", "browser.mcp.status")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "mcp.tools" to ::listTools,
        "mcp.status" to ::status,
    )

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
                        appendLine("  - `$k`: ${v["description"] ?: v["type"] ?: ""}")
                    }
                }
                appendLine()
            }
        }
        return ExecutionResult.ok(output)
    }

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("浏览器 MCP 服务已就绪，共 ${getTools().size} 个工具。")
    }
}
