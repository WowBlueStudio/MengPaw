// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广告拦截规则单测 (browser 首测, 2026-08-07 Codex 交接补充)。
 *
 * 覆盖 P2 fix 语义: 域名 host 逐段精确匹配(不误伤子串相似域名) / 子域继承 /
 * 带路径规则(fa-facebook.com/tr) / 模式匹配(ad/tracker/pixel/utm) / 非法 URL 安全降级。
 */
class AdBlockerTest {

    @Test
    fun 精确域名命中() {
        assertTrue(isAdRequest("https://doubleclick.net/ads"))
        assertTrue(isAdRequest("https://www.googleadservices.com/pagead/"))
        assertTrue(isAdRequest("https://analytics.google.com/x"))
    }

    @Test
    fun 子串相似域名不误伤() {
        // P2 回归: 原子串匹配会误拦这些合法域名
        assertFalse(isAdRequest("https://notdoubleclick.net/"))
        assertFalse(isAdRequest("https://adservice.google.com.cn/"))
        assertFalse(isAdRequest("https://myanalytics.example.com/"))
    }

    @Test
    fun 子域继承命中() {
        assertTrue(isAdRequest("https://cdn.doubleclick.net/"))
        assertTrue(isAdRequest("https://pagead2.googlesyndication.com/"))
    }

    @Test
    fun 带路径规则仅路径匹配() {
        assertTrue(isAdRequest("https://www.facebook.com/tr"))
        assertTrue(isAdRequest("https://www.facebook.com/tr/event"))
        // 路径不匹配时不因域名规则误拦 facebook.com 本体
        assertFalse(isAdRequest("https://www.facebook.com/home"))
    }

    @Test
    fun 路径模式命中() {
        assertTrue(isAdRequest("https://example.com/ads/banner.png"))
        assertTrue(isAdRequest("https://example.com/js/tracker.js"))
        assertTrue(isAdRequest("https://example.com/pixel.gif"))
    }

    @Test
    fun 查询参数模式命中() {
        assertTrue(isAdRequest("https://example.com/article?utm_source=newsletter"))
        assertTrue(isAdRequest("https://example.com/?ref=sponsor"))
        assertTrue(isAdRequest("https://example.com/?gclid=abc"))
    }

    @Test
    fun 普通页面不误拦() {
        assertFalse(isAdRequest("https://example.com/article"))
        assertFalse(isAdRequest("https://blog.example.com/post"))
    }

    @Test
    fun 非法URL安全降级() {
        assertFalse(isAdRequest("not a url"))
        assertFalse(isAdRequest(""))
        assertFalse(isAdRequest("javascript:alert(1)"))
    }
}
