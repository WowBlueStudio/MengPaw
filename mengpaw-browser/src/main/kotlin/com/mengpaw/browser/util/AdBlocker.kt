// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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

fun isAdRequest(url: String): Boolean {
    val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
    return AD_DOMAINS.any { host.contains(it, ignoreCase = true) } ||
           AD_PATTERNS.any { it.containsMatchIn(url) }
}
