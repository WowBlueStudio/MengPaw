// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.data

// ── Types ──────────────────────────────────────────────────────────

data class TabState(
    val id: Int, val url: String = "", val title: String = "",
    val isLoading: Boolean = false, val progress: Int = 0,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false
)

data class DetectedImage(
    val src: String, val alt: String, val width: Int = 0, val height: Int = 0, val z: Int = 0,
    val mediaType: String = "image" // "image" or "video"
)

enum class SearchEngine(val label: String, val url: String, val key: String) {
    GOOGLE("Google", "https://www.google.com/search?q=", "google"),
    BING("Bing", "https://www.bing.com/search?q=", "bing"),
    BAIDU("百度", "https://www.baidu.com/s?wd=", "baidu"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=", "duckduckgo");

    companion object {
        fun fromKey(key: String) = entries.find { it.key == key } ?: BING
    }
}
