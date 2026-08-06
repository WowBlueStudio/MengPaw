// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import com.mengpaw.browser.data.SearchEngine
import java.net.URLEncoder

/**
 * Smart URL detection: returns search URL for keywords, original URL with https for domains.
 *
 * P2 fix: 纯数字/小数 (如 "3.14") 不再误判为域名 — 末段不含字母 (非 TLD 形态) 时按搜索处理。
 * 域名判定: 含 '.' 无空格, 最后一个 '.' 之后含字母 (TLD), 且整体仅含 URL 合法字符。
 */
fun smartNavigate(input: String, engine: SearchEngine): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    // Already a full URL
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    // Contains a dot and no spaces → treat as domain only if the TLD part looks real
    if (trimmed.contains(".") && !trimmed.contains(" ")) {
        val lastDot = trimmed.lastIndexOf('.')
        val tld = if (lastDot > 0) trimmed.substring(lastDot + 1) else ""
        val looksLikeDomain = tld.isNotEmpty() &&
            tld.any { it.isLetter() } &&
            trimmed.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        if (looksLikeDomain) return "https://$trimmed"
    }
    // Fallback: search engine
    return engine.url + URLEncoder.encode(trimmed, "UTF-8")
}
