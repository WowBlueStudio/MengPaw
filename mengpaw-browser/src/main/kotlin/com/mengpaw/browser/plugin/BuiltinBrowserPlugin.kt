// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import android.webkit.WebView
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * Tab metadata exposed to Agent for multi-tab control.
 */
data class BrowserTab(
    val id: Int,
    val url: String,
    val title: String,
    val isLoading: Boolean,
    val isActive: Boolean
)

/**
 * Built-in browser plugin providing browser.* CLI commands for Agent.
 *
 * ## Multi-tab control (4 tabs max)
 *   browser.tabs                — list all tabs
 *   browser.tab <N>             — switch to tab N
 *   browser.tab.open <N> <url>  — open URL in tab N (auto-creates if needed)
 *   browser.tab.close <N>       — close tab N
 *   browser.tab.all             — extract content from ALL tabs in one call
 *
 * ## Efficiency commands
 *   browser.nav <url>           — navigate + wait + auto-extract content
 *   browser.batch <cmd1;;cmd2>  — execute multiple commands in one round-trip
 *   browser.q <shorthand>       — quick selector shortcuts
 *
 * ## Basic control
 *   browser.eval / click / type / scroll / content / screenshot
 *   browser.open / back / forward / title / url
 *
 * v0.32.x (400 行文件拆分批次 2): 命令实现按组拆至
 *   [BrowserTabCommands]   (标签页 + 效率)
 *   [BrowserPageCommands]  (页面控制/等待/Cookie/对话框)
 *   [BrowserQueryCommands] (表单/查询/截图/坐标/视口)
 * v0.8.0 (半自动武器 Phase 1): 新增 [BrowserPlaywrightCommands] — page.* Playwright
 * 语义命令组（22 条）。v0.8.0 Phase 3 去重后 browser.* 保留 23 条（被 page.* 覆盖
 * 的命令已删，决策 #4）— 合计 45 条命令。
 * 本类保留构造参数 + commands 聚合 + companion 开关, 公开 API 不变。
 */
class BuiltinBrowserPlugin(
    private val webViewProvider: () -> WebView?,
    private val tabInfoProvider: () -> List<BrowserTab> = { emptyList() },
    private val tabSwitcher: (Int) -> Unit = {},
    private val tabOpener: (Int, String) -> Unit = { _, _ -> },
    private val tabCloser: (Int) -> Unit = {}
) {
    private val ctx = BrowserCommandContext(
        webViewProvider, tabInfoProvider, tabSwitcher, tabOpener, tabCloser
    )

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> =
        BrowserTabCommands(ctx).commands +
        BrowserPageCommands(ctx).commands +
        BrowserQueryCommands(ctx).commands +
        BrowserPlaywrightCommands(ctx).commands

    companion object {
        /** Providers set by BrowserActivity for toggle-aware command execution. */
        @JvmStatic var quickClickEnabled: () -> Boolean = { true }
        @JvmStatic var screenshotMaxHeight: () -> Int = { 15000 }

        /** am 桥共享实例 — BrowserActivity 初始化，RunCommandService 复用（半自动武器 Phase 2）。 */
        @Volatile
        @JvmStatic
        var shared: BuiltinBrowserPlugin? = null
    }
}
