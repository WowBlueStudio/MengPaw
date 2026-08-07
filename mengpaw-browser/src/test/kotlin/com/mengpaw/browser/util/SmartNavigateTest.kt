// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import com.mengpaw.browser.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能导航 URL 判定单测 (browser 首测, 2026-08-07 Codex 交接补充)。
 *
 * 覆盖 P2 fix 语义: 完整 URL 原样返回 / 域名补 https / 纯数字与小数不误判为域名 /
 * 关键词走搜索引擎 / 空输入返回空串。
 */
class SmartNavigateTest {

    @Test
    fun 完整URL原样返回() {
        assertEquals("http://example.com", smartNavigate("http://example.com", SearchEngine.BING))
        assertEquals("https://example.com/x", smartNavigate("https://example.com/x", SearchEngine.BING))
    }

    @Test
    fun 纯域名补https前缀() {
        assertEquals("https://example.com", smartNavigate("example.com", SearchEngine.BING))
        assertEquals("https://www.mengpaw.dev", smartNavigate("www.mengpaw.dev", SearchEngine.BING))
    }

    @Test
    fun 子域与连字符域名合法() {
        assertEquals("https://sub.domain.org", smartNavigate("sub.domain.org", SearchEngine.BING))
        assertEquals("https://my-site.io", smartNavigate("my-site.io", SearchEngine.BING))
    }

    @Test
    fun 纯数字不误判为域名() {
        // P2 回归: "3.14" 末段无字母, 按搜索处理而非 https://3.14
        val r = smartNavigate("3.14", SearchEngine.BING)
        assertTrue(r.startsWith("https://www.bing.com/search?q=3.14"))
    }

    @Test
    fun 关键词走搜索引擎() {
        val r = smartNavigate("hello world", SearchEngine.BAIDU)
        assertEquals("https://www.baidu.com/s?wd=hello+world", r)
    }

    @Test
    fun 中文关键词URL编码() {
        val r = smartNavigate("檬爪框架", SearchEngine.GOOGLE)
        assertTrue(r.startsWith("https://www.google.com/search?q="))
        assertTrue(r.contains("%E6%AA%AC%E7%88%AA"))
    }

    @Test
    fun 空白输入返回空串() {
        assertEquals("", smartNavigate("   ", SearchEngine.BING))
        assertEquals("", smartNavigate("", SearchEngine.BING))
    }

    @Test
    fun 非法字符域名按搜索处理() {
        // 含空格不是域名 → 搜索; 含非法字符 (!) 也不是域名 → 搜索
        assertTrue(smartNavigate("exa mple.com", SearchEngine.BING).startsWith("https://www.bing.com/search?q="))
        assertTrue(smartNavigate("example!.com", SearchEngine.BING).startsWith("https://www.bing.com/search?q="))
    }

    @Test
    fun fromKey未知键回退BING() {
        assertEquals(SearchEngine.BING, SearchEngine.fromKey("no-such-engine"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromKey("google"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromKey("duckduckgo"))
    }
}
