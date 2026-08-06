// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

// ── Ad Block List ──────────────────────────────────────────────────

private val AD_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
    "adservice.google.com", "adservice.google.nl", "pagead2.googlesyndication.com",
    "amazon-adsystem.com", "criteo.com", "criteo.net", "adsrvr.org", "adnxs.com",
    "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
    "smartadserver.com", "outbrain.com", "taboola.com", "moatads.com",
    "advertising.com", "serving-sys.com", "adsafeprotected.com", "yieldmo.com",
    "scorecardresearch.com", "quantserve.com", "bluekai.com", "exelator.com",
    "demdex.net", "ads.linkedin.com", "ads.twitter.com", "ads.yahoo.com",
    "analytics.google.com", "googletagmanager.com", "facebook.com/tr",
    "bat.bing.com", "clarity.ms", "hotjar.com", "mouseflow.com"
)

private val AD_PATTERNS = listOf(
    Regex("[/.](?:ad|ads|advert|banner|popup|popunder|sponsor)[s]?[/.]", RegexOption.IGNORE_CASE),
    Regex("[/.](?:tracker|tracking|pixel|beacon|analytics|stat)[s]?[/.]", RegexOption.IGNORE_CASE),
    Regex("[?&](?:utm_|ref=|sponsored|adid|gclid|fbclid)", RegexOption.IGNORE_CASE)
)

/**
 * P2 fix: 域名规则从子串匹配升级为 host 逐段精确匹配。
 * 原子串匹配 (`host.contains(rule)`) 会误拦合法域名 — 如 "doubleclick.net" 命中
 * "notdoubleclick.net"、"adservice.google.com" 命中 "adservice.google.com.cn"。
 * 规则语义: host == 规则 或 host 以 ".规则" 结尾 (子域继承), 规则带路径时 (如
 * "facebook.com/tr") 额外校验 URL 路径前缀。规则集合保持不变。
 */
private fun hostMatches(host: String, rule: String): Boolean {
    val h = host.lowercase().removeSuffix(".")
    val r = rule.lowercase().removeSuffix(".")
    return h == r || h.endsWith(".$r")
}

fun isAdRequest(url: String): Boolean {
    val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
    val host = uri.host ?: return false
    val path = uri.path ?: ""
    val domainHit = AD_DOMAINS.any { rule ->
        // 规则可含路径段 ("facebook.com/tr") — 域名与路径分开精确匹配
        val slash = rule.indexOf('/')
        val ruleHost = if (slash >= 0) rule.substring(0, slash) else rule
        val rulePath = if (slash >= 0) rule.substring(slash + 1) else null
        if (!hostMatches(host, ruleHost)) return@any false
        if (rulePath == null) return@any true
        path == "/$rulePath" || path.startsWith("/$rulePath/")
    }
    return domainHit || AD_PATTERNS.any { it.containsMatchIn(url) }
}
