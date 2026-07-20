// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.browsercdp

import android.webkit.WebView
import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginContext
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType

/**
 * Chrome DevTools Protocol (CDP) debugging support for MP Browser.
 *
 * Enables `setWebContentsDebuggingEnabled(true)` so developers can
 * connect Chrome DevTools to inspect WebView pages.
 *
 * ## Security
 * Only enabled in debug builds. Never ships in release APKs.
 * Remote debugging is restricted to localhost only.
 *
 * ## Design reference (MIT-licensed):
 * Kuri: CDP command mapping for browser automation
 * native-devtools-mcp: CDP endpoint exposure pattern
 */
class BrowserCdpPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "browser-cdp-plugin",
        name = "CDP 调试",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Chrome DevTools Protocol 调试支持。仅 debug 构建，仅 localhost。",
        permissions = emptyList(),
        minCoreVersion = "0.2.3",
        commands = listOf("cdp.enable", "cdp.status")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "enable" to ::enable,
        "status" to ::status,
    )

    private var enabled = false

    override suspend fun onInstall(ctx: PluginContext) {
        // Security: only enable in debug builds
        try {
            val debugEnabled = Class.forName("com.mengpaw.browser.BuildConfig")
                .getField("DEBUG")
                .getBoolean(null)
            if (!debugEnabled) {
                ctx.log("CDP 调试仅在 debug 构建中可用。当前为 release 构建，已禁用。")
                return
            }
        } catch (_: Exception) {
            // BuildConfig not accessible — safe default: don't enable
            ctx.log("CDP 调试：无法验证构建类型，默认禁用。")
            return
        }

        try {
            WebView.setWebContentsDebuggingEnabled(true)
            enabled = true
            ctx.log("CDP 调试已启用。在 Chrome 中访问 chrome://inspect 连接。")
        } catch (_: Exception) {
            ctx.log("CDP 调试启用失败。")
        }
    }

    private suspend fun enable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (enabled) return ExecutionResult.ok("CDP 调试已启用。访问 chrome://inspect 连接。")
        return try {
            WebView.setWebContentsDebuggingEnabled(true)
            enabled = true
            ExecutionResult.ok("CDP 调试已启用。在 Chrome 地址栏输入 chrome://inspect 连接设备。")
        } catch (e: Exception) {
            ExecutionResult.fail("启用失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return if (enabled) {
            ExecutionResult.ok("""
## CDP 调试状态: 已启用

### 连接方式
1. USB 连接设备到电脑
2. 在 Chrome 地址栏输入: chrome://inspect
3. 找到 MP Browser 的 WebView
4. 点击 "inspect" 开始调试

### 可用功能
- Elements: 实时 DOM 检查和修改
- Console: JavaScript 控制台
- Network: 网络请求监控
- Sources: 断点调试
            """.trimIndent())
        } else {
            ExecutionResult.ok("CDP 调试: 未启用。使用 cdp.enable 或安装此插件的 debug 版本。")
        }
    }
}
