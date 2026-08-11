// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.webkit.WebView
import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.browser.plugin.BuiltinBrowserPlugin

/**
 * 设备内 MCP 工具执行（自 BrowserActivity 拆出 — 400 行文件拆分批次 2）。
 *
 * 双路径分流:
 * - **内置命令** (BuiltinBrowserPlugin, page.* 22 + browser.* 23): 后台线程直接执行。命令内部经
 *   webView.post 自行回主线程, 原 runOnUiThread 方案会让主线程阻塞在 evalJs 的
 *   latch.await 上, post 的 runnable 永远排不上 → 每次调用 2s 超时 (隐形失效)。
 * - **原生 6 工具** (navigate/screenshot/click/type/extract/eval): 主线程执行
 *   (browser_screenshot 的 View.draw 必须主线程) — 非主线程经 [onMainThread] 转派。
 */

/** MCP map 参数 → 位置参数映射键序 (多参数命令按此顺序取)。 */
private val POS_ARG_KEYS = listOf(
    "url", "selector", "text", "x", "y", "script", "value", "name",
    "width", "height", "css", "n", "seg", "dy", "type", "op", "key",
    "domain", "target", "attribute"
)

/** MCP map 参数 → --flag 值映射 (page.* 命令的带值 flag, v0.8.0 半自动武器)。 */
private val FLAG_ARG_MAP = mapOf(
    "maxHeight" to "--max-height",
    "timeoutMs" to "--timeout",
    "wait" to "--wait",
    "grep" to "--grep",
    "head" to "--head",
    "tail" to "--tail"
)

/** MCP map 参数 → 布尔 flag 映射 (值为 true/非空时附加)。 */
private val BOOL_FLAG_MAP = mapOf(
    "full" to "--full",
    "view" to "--view",
    "regex" to "--regex",
    "ignoreCase" to "-i"
)

/**
 * MCP 工具入口 (HTTP server 线程调用)。
 * @param onMainThread 主线程执行器 — Activity 注入 (runOnUiThread + latch)。
 */
internal fun runMcpTool(
    builtinPlugin: BuiltinBrowserPlugin,
    webViewProvider: () -> WebView?,
    onMainThread: (block: () -> Unit) -> Unit,
    toolName: String,
    args: Map<String, String>
): String {
    if (builtinPlugin.commands.containsKey(toolName)) {
        return executeBuiltinTool(builtinPlugin, toolName, args)
    }
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        return executeMcpTool(webViewProvider, toolName, args)
    }
    val latch = java.util.concurrent.CountDownLatch(1)
    var result = """{"ok":false,"error":"timeout"}"""
    onMainThread {
        try {
            result = executeMcpTool(webViewProvider, toolName, args)
        } catch (e: Exception) {
            result = """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        } finally {
            latch.countDown()
        }
    }
    latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
    return result
}

/**
 * 内置命令执行器 (BuiltinBrowserPlugin — 命令键直接作 MCP 工具名,
 * Agent 经 browser.mcp.invoke <命令> 调用)。
 */
private fun executeBuiltinTool(
    builtinPlugin: BuiltinBrowserPlugin,
    toolName: String,
    args: Map<String, String>
): String {
    val handler = builtinPlugin.commands[toolName]
        ?: return """{"ok":false,"error":"Unknown tool: $toolName"}"""
    val positional = mcpArgsToPositional(args)
    val ctx = com.mengpaw.kernel.cli.ExecutionContext(sessionId = "mcp", userId = "mcp")
    return try {
        // 命令为 suspend — 在 HTTP server 线程上 runBlocking 执行;
        // 命令内部经 webView.post 自行回主线程, 无主线程死锁
        val result = kotlinx.coroutines.runBlocking { handler(positional, ctx) }
        if (result.success) result.output else result.error ?: "命令执行失败"
    } catch (e: Exception) {
        """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
    }
}

/** MCP 参数 map → 内置命令位置参数列表 (位置键 + --flag 展开; 无命中按值序兜底)。 */
private fun mcpArgsToPositional(args: Map<String, String>): List<String> {
    val ordered = mutableListOf<String>()
    POS_ARG_KEYS.forEach { k -> args[k]?.let { ordered.add(it) } }
    FLAG_ARG_MAP.forEach { (k, flag) -> args[k]?.let { v -> ordered.add(flag); ordered.add(v) } }
    BOOL_FLAG_MAP.forEach { (k, flag) ->
        args[k]?.let { v -> if (v.isNotBlank() && v != "false") ordered.add(flag) }
    }
    return if (ordered.isNotEmpty()) ordered else args.values.toList()
}

/** MCP tool executor — 直接操作 WebView (必须在主线程调用)。 */
private fun executeMcpTool(
    webViewProvider: () -> WebView?,
    toolName: String,
    args: Map<String, String>
): String {
    val wv = webViewProvider()
        ?: return """{"ok":false,"error":"WebView not available"}"""
    val bridge = BrowserBridge(wv)
    return try {
        when (toolName) {
            "browser_navigate" -> {
                val url = args["url"] ?: return """{"ok":false,"error":"Missing 'url'"}"""
                wv.loadUrl(url)
                // Wait for page to finish loading (max 10s)
                var waited = 0
                while (wv.progress < 100 && waited < 100) {
                    Thread.sleep(100); waited++
                }
                """{"ok":true}"""
            }
            "browser_screenshot" -> bridge.screenshot()
            "browser_click" -> {
                val sel = args["selector"] ?: return """{"ok":false,"error":"Missing 'selector'"}"""
                bridge.click(sel)
            }
            "browser_type" -> bridge.type(args["selector"] ?: "", args["text"] ?: "")
            "browser_extract" -> bridge.content()
            "browser_eval" -> {
                val script = args["script"] ?: return """{"ok":false,"error":"Missing 'script'"}"""
                bridge.eval(script)
            }
            else -> """{"ok":false,"error":"Unknown tool: $toolName"}"""
        }
    } catch (e: Exception) {
        """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
    }
}
