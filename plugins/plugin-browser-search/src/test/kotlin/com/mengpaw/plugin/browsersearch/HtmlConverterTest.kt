// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.browsersearch

import org.junit.Assert.*
import org.junit.Test

/** HtmlConverter 纯转换器测试 — 零依赖, 直接断言输出。 */
class HtmlConverterTest {

    @Test
    fun `h1 to h6 map to markdown headings`() {
        val md = HtmlConverter.toMarkdown(
            "<h1>一级</h1><h2>二级</h2><h3>三级</h3>", null
        )
        assertEquals("# 一级", md.lines()[0].trim())
        assertEquals("## 二级", md.lines()[1].trim())
        assertEquals("### 三级", md.lines()[2].trim())
    }

    @Test
    fun `list items become dash bullets`() {
        val md = HtmlConverter.toMarkdown("<ul><li>苹果</li><li>香蕉</li></ul>", null)
        assertTrue(md.contains("- 苹果"))
        assertTrue(md.contains("- 香蕉"))
    }

    @Test
    fun `anchors become markdown links`() {
        val md = HtmlConverter.toMarkdown(
            "点击<a href=\"https://example.com/x\">这里</a>查看", null
        )
        assertTrue(md.contains("[这里](https://example.com/x)"))
    }

    @Test
    fun `images become markdown images`() {
        val md = HtmlConverter.toMarkdown(
            "<img src=\"/pic.png\" alt=\"截图\">", null
        )
        assertTrue(md.contains("![截图](/pic.png)"))
    }

    @Test
    fun `table becomes pipe table`() {
        val md = HtmlConverter.toMarkdown(
            "<table><tr><th>名称</th><th>值</th></tr><tr><td>a</td><td>1</td></tr></table>", null
        )
        assertTrue(md.contains("| 名称"))
        assertTrue(md.contains("| a"))
        assertTrue(md.contains("| 1"))
    }

    @Test
    fun `noise tags are stripped`() {
        val md = HtmlConverter.convert(
            "<html><head><title>测试页</title></head><body><article>" +
            "<nav>导航</nav><script>var x=1;</script>" +
            "<p>正文内容</p><div class=\"ad-banner\">广告</div>" +
            "<footer>页脚</footer></article></body></html>",
            "测试页"
        )
        assertTrue(md.startsWith("# 测试页"))
        assertTrue(md.contains("正文内容"))
        assertFalse(md.contains("导航"))
        assertFalse(md.contains("var x=1"))
        assertFalse(md.contains("广告"))
        assertFalse(md.contains("页脚"))
    }

    @Test
    fun `article container is preferred over body`() {
        val html = "<body><div class=\"header\">头部</div><article><p>文章正文</p></article></body>"
        val container = HtmlConverter.extractMainContainer(html)
        assertTrue(container.startsWith("<article"))
        assertTrue(HtmlConverter.toMarkdown(container, null).contains("文章正文"))
    }

    @Test
    fun `entities are decoded`() {
        val md = HtmlConverter.toMarkdown("<p>AT&amp;T &lt;b&gt; &amp;nbsp; 结束</p>", null)
        assertTrue(md.contains("AT&T <b>"))
    }

    @Test
    fun `sanitize filename removes unsafe chars`() {
        assertEquals("a_b_c", HtmlConverter.sanitizeFileName("a/b\\c"))
        assertEquals("page", HtmlConverter.sanitizeFileName("   "))
    }

    @Test
    fun `pre blocks become code fences`() {
        val md = HtmlConverter.toMarkdown(
            "<pre><code>val x = 1</code></pre>", null
        )
        assertTrue(md.contains("```"))
        assertTrue(md.contains("val x = 1"))
    }
}
