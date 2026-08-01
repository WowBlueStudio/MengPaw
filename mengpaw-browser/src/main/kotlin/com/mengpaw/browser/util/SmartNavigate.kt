// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import com.mengpaw.browser.data.SearchEngine
import java.net.URLEncoder

/** Smart URL detection: returns search URL for keywords, original URL with https for domains. */
fun smartNavigate(input: String, engine: SearchEngine): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    // Already a full URL
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    // Contains a dot and no spaces → treat as domain
    if (trimmed.contains(".") && !trimmed.contains(" ")) return "https://$trimmed"
    // Fallback: search engine
    return engine.url + URLEncoder.encode(trimmed, "UTF-8")
}
