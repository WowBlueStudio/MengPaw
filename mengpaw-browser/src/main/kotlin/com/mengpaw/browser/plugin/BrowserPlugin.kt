// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.plugin

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.plugin.CommandHandler
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata

/**
 * Browser extension plugin — extends the core Plugin interface
 * with WebView lifecycle hooks and content APIs.
 *
 * Community developers implement this to add browser features:
 * - Ad blocking rules
 * - Video/image downloaders
 * - Dark mode / reader mode
 * - Translation
 * - User scripts (Tampermonkey-style)
 *
 * Agent can also use [BrowserPluginRegistry.getCapabilities()]
 * via CLI to discover available browser hooks and generate plugins.
 */
interface BrowserPlugin : Plugin {

    // ── WebView lifecycle hooks ──────────────────────────────────

    /** Called when a page starts loading. */
    fun onPageStarted(url: String) {}

    /** Called when a page finishes loading. Return a JS script to inject. */
    fun onPageFinished(url: String, title: String): String? = null

    // ── Content & interception ───────────────────────────────────

    /** Intercept a resource request. Return a non-null response to block. */
    fun shouldIntercept(request: WebResourceRequest): WebResourceResponse? = null

    /** Return JS to inject into every page (user scripts). */
    fun injectScript(url: String): String? = null

    /** Return CSS to inject into every page. */
    fun injectStyle(url: String): String? = null

    // ── UI extensions ────────────────────────────────────────────

    /** Additional menu items to append to the browser's drop-down menu.
     *  Each item is a (label, iconName, onClickKey) tuple.
     *  onClickKey maps to a CLI command like "my-plugin.doAction". */
    fun menuItems(): List<BrowserMenuItem> = emptyList()

    /** Called when a long-press element is detected. Return menu actions. */
    fun onLongPress(element: BrowserElement): List<BrowserAction> = emptyList()
}

/** Menu item contributed by a browser plugin. */
data class BrowserMenuItem(
    val label: String,
    val command: String  // CLI command to execute when clicked, e.g. "video.download"
)

/** Element info detected on long press (passed to plugins). */
data class BrowserElement(
    val type: String,       // "IMAGE", "VIDEO", "LINK", "QR"
    val url: String,
    val alt: String = "",
    val width: Int = 0,
    val height: Int = 0
)

/** Action contributed by a plugin for a long-press element. */
data class BrowserAction(
    val label: String,
    val command: String     // CLI command, e.g. "video.download <url>"
)
