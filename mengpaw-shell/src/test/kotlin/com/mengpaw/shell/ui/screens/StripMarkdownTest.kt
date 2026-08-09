// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡复制/分享/大爆炸文本处理回归锁 (v0.35.2 审查):
 * 复制保留 markdown 原文 + 换行规范化; 大爆炸/分享剥离 markdown 且保留分行。
 */
class StripMarkdownTest {

    // ── 换行规范化 (复制路径) ──────────────────────────────────────

    @Test
    fun `normalizeNewlines unifies CRLF and CR to LF`() {
        assertEquals("a\nb\nc", normalizeNewlines("a\r\nb\rc"))
        assertEquals("保留原有换行\n第二行", normalizeNewlines("保留原有换行\n第二行"))
    }

    @Test
    fun `normalizeNewlines keeps markdown intact`() {
        val md = "## 标题\n\n- 列表项一\n- 列表项二"
        assertEquals(md, normalizeNewlines(md))
    }

    // ── Markdown 剥离 (分享/大爆炸路径) ─────────────────────────────

    @Test
    fun `stripMarkdown removes inline markers`() {
        val input = "**加粗** 与 *斜体* 与 `代码` 与 ~~删除~~"
        assertEquals("加粗 与 斜体 与 代码 与 删除", stripMarkdown(input))
    }

    @Test
    fun `stripMarkdown converts link and drops image`() {
        assertEquals("点这里", stripMarkdown("[点这里](https://example.com)"))
        assertEquals("文字", stripMarkdown("![图片](file.png) 文字"))
    }

    @Test
    fun `stripMarkdown removes heading quote and code fences`() {
        assertEquals("标题\n引用\n正文", stripMarkdown("## 标题\n> 引用\n正文"))
        assertEquals("代码内容", stripMarkdown("```\n代码内容\n```"))
    }

    @Test
    fun `stripMarkdown keeps line breaks for lists`() {
        val input = "- 第一项\n- 第二项\n1. 第三项"
        val out = stripMarkdown(input)
        assertTrue("列表项应保留换行: $out", out.contains("\n"))
        assertTrue("无序列表转圆点", out.contains("• 第一项"))
        assertTrue("有序列表去序号", out.contains("第三项"))
    }

    @Test
    fun `stripMarkdown converts soft line break to hard`() {
        assertEquals("第一行\n第二行", stripMarkdown("第一行  \n第二行"))
    }

    @Test
    fun `stripMarkdown preserves paragraphs`() {
        val input = "第一段\n\n第二段"
        assertEquals(input, stripMarkdown(input))
    }
}
