// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

import android.content.Context

/** Persistent browser settings backed by SharedPreferences. */
class BrowserPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("mp_browser", Context.MODE_PRIVATE)

    var adBlockEnabled: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    /** Ordered list of enabled engine keys (comma-separated). */
    var engineKeys: List<String>
        get() = (p.getString("engines", "bing,google,baidu,duckduckgo") ?: "bing,google,baidu,duckduckgo").split(",").filter { it.isNotBlank() }
        set(v) = p.edit().putString("engines", v.joinToString(",")).apply()

    /** Last-used engine key. */
    var lastEngineKey: String
        get() = p.getString("last_engine", "bing") ?: "bing"
        set(v) = p.edit().putString("last_engine", v).apply()

    /** Get the ordered list of enabled SearchEngine instances. */
    fun enabledEngines(): List<SearchEngine> = engineKeys.mapNotNull { SearchEngine.fromKey(it) }

    /** Current default engine (last used). */
    fun defaultEngine(): SearchEngine = SearchEngine.fromKey(lastEngineKey)

    /** Set a new default and persist. */
    fun setDefaultEngine(engine: SearchEngine) { lastEngineKey = engine.key }

    var historyEnabled: Boolean
        get() = p.getBoolean("history_enabled", true)
        set(v) = p.edit().putBoolean("history_enabled", v).apply()

    // ── 主页 ──

    /** 主页 URL (Home 按钮目标; P2 fix: 消除 BrowserTopBar 硬编码 baidu, 改为持久化设置)。 */
    var homeUrl: String
        get() = p.getString("home_url", "https://www.baidu.com") ?: "https://www.baidu.com"
        set(v) = p.edit().putString("home_url", v).apply()
    // 注意: savePasswords pref 已随 P2 修复移除 — WebView API 18+ 无密码保存能力, 假开关无意义

    // ── Agent Collaboration Settings ──

    /** Quick Click: full-page screenshot + coordinate taps (experimental, default ON) */
    var quickClickEnabled: Boolean
        get() = p.getBoolean("quick_click", true)
        set(v) = p.edit().putBoolean("quick_click", v).apply()

    /** Auto-inject the __mp bridge on every page load for faster commands */
    var autoInjectBridge: Boolean
        get() = p.getBoolean("auto_inject", true)
        set(v) = p.edit().putBoolean("auto_inject", v).apply()

    /** Max height (pixels) for full-page screenshots. Default 15000, range 5000-30000 */
    var screenshotMaxHeight: Int
        get() = p.getInt("screenshot_max_h", 15000).coerceIn(5000, 30000)
        set(v) = p.edit().putInt("screenshot_max_h", v.coerceIn(5000, 30000)).apply()

    /** Screenshot JPEG quality percentage (for full-page, lower = smaller file) */
    var screenshotQuality: Int
        get() = p.getInt("screenshot_quality", 85).coerceIn(30, 100)
        set(v) = p.edit().putInt("screenshot_quality", v.coerceIn(30, 100)).apply()

    var darkMode: Boolean
        get() = p.getBoolean("dark_mode", false)
        set(v) = p.edit().putBoolean("dark_mode", v).apply()

    // ── MCP 开放控制 (v0.8.x 第三方接入) ──

    /**
     * MCP 桥开放模式 (Playwright 式): 开启后 127.0.0.1:9880 的 /mcp 免 Bearer token,
     * 本机任意进程可直接控制浏览器; 默认关闭保持签名级安全模型。
     */
    var mcpOpenMode: Boolean
        get() = p.getBoolean("mcp_open_mode", false)
        set(v) = p.edit().putBoolean("mcp_open_mode", v).apply()

    // ── Bookmarks ──
    var bookmarks: List<String>
        get() = (p.getString("bookmarks", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = p.edit().putString("bookmarks", v.joinToString(",")).apply()

    fun addBookmark(url: String) { val list = bookmarks.toMutableList(); if (url !in list) { list.add(0, url); bookmarks = list } }

    fun removeBookmark(url: String) { bookmarks = bookmarks.filter { it != url } }

    fun isBookmarked(url: String): Boolean = url in bookmarks

    // ── Tab session persistence ──
    var savedTabUrls: List<String>
        get() = (p.getString("tab_urls", "") ?: "").split("|").filter { it.isNotBlank() }
        set(v) = p.edit().putString("tab_urls", v.joinToString("|")).apply()

    var savedActiveTabId: Int
        get() = p.getInt("active_tab_id", -1)
        set(v) = p.edit().putInt("active_tab_id", v).apply()
}
