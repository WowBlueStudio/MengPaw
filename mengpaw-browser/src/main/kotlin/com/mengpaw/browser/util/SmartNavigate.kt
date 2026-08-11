// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import com.mengpaw.browser.data.SearchEngine
import java.net.URLEncoder

/**
 * Smart URL detection: returns search URL for keywords, original URL with https for domains.
 *
 * P2 fix: 纯数字/小数 (如 "3.14") 不再误判为域名 — 末段不含字母 (非 TLD 形态) 时按搜索处理。
 * v0.8.0 中文支持: ① 域名判定基于 host 段 (首个 / ? # 之前) — 支持带路径/查询的无协议 URL
 * (如 `zh.wikipedia.org/wiki/中文`); ② 允许非 ASCII (中文) 字符, 中文路径 URL 不再误判为搜索;
 * ③ [decodeUrlForDisplay] 把百分号编码还原为中文等可读字符 (地址栏显示用)。
 */
fun smartNavigate(input: String, engine: SearchEngine): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    // Already a full URL
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    // Contains a dot and no spaces → treat as domain only if the TLD part looks real
    if (trimmed.contains(".") && !trimmed.contains(" ")) {
        // host 段 = 首个 / ? # 之前 (路径/查询可含中文与 URL 字符)
        val hostPart = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
        val lastDot = hostPart.lastIndexOf('.')
        val tld = if (lastDot > 0) hostPart.substring(lastDot + 1) else ""
        val looksLikeDomain = tld.isNotEmpty() &&
            tld.any { it.isLetter() } &&
            isUrlLike(trimmed)
        if (looksLikeDomain) return "https://$trimmed"
    }
    // Fallback: search engine
    return engine.url + URLEncoder.encode(trimmed, "UTF-8")
}

/** URL 合法字符 (RFC 3986 常用子集) + 非 ASCII (中文等)。! 排除 (沿用保守约定, 防误判)。 */
private fun isUrlLike(s: String): Boolean =
    s.all { c -> c.isLetterOrDigit() || c.code > 127 || c in ".-_/?:#=&%+~@$'()*," }

/**
 * URL 显示解码 — 百分号编码 (UTF-8) 还原为中文等可读字符，供地址栏显示。
 * 仅解码 %XX；query 的 `+` 先转义保留字面（不做空格语义转换）。解码失败回退原串。
 */
fun decodeUrlForDisplay(url: String): String {
    if (url.indexOf('%') < 0) return url
    return try {
        java.net.URLDecoder.decode(url.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        url
    }
}
