// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import android.webkit.WebView
import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * 浏览器命令共享上下文（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 *
 * 42 条 browser.* 命令实现按组拆至 [BrowserTabCommands]/[BrowserPageCommands]/
 * [BrowserQueryCommands] — 各组类持有本 context, 公开 API 与行为零变化。
 */
internal class BrowserCommandContext(
    val webViewProvider: () -> WebView?,
    val tabInfoProvider: () -> List<BrowserTab>,
    val tabSwitcher: (Int) -> Unit,
    val tabOpener: (Int, String) -> Unit,
    val tabCloser: (Int) -> Unit
) {
    val bridge: BrowserBridge? get() {
        val wv = webViewProvider() ?: return null
        return BrowserBridge(wv)
    }

    fun noBrowser(): ExecutionResult =
        ExecutionResult.fail("浏览器未就绪，请先打开 MP 浏览器", errorCode = ErrorCodes.ERR_INTERNAL)
}
