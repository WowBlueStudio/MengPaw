// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.browsersearch

import java.io.File

/**
 * Lightweight HTML → 正文 / Markdown converter (zero dependencies).
 *
 * 启发式正文提取复刻 BrowserReaderMode 的 JS 逻辑:
 * 语义容器优先 (`<article>` → `[role=main]` → `#content` → `.post` → `<main>` → `<body>`),
 * 再剥离 script/style/nav/footer/header/ad/comment 等噪声。
 *
 * Markdown 转换用标签扫描 (h1-h6→#、li→`- `、a→`[text](href)`、img→`![]()`、
 * table→管道表、pre→代码块), 不做完整 HTML 解析 — 覆盖常见文章页足够,
 * 且可在 JVM 单元测试覆盖。
 */
object HtmlConverter {

    private val COMMENT = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG = Regex("<\\/?\\s*([a-zA-Z0-9]+)([^>]*)>")
    private val ATTR = Regex("([a-zA-Z-]+)=\"([^\"]*)\"")

    /** 提取主正文容器 HTML (启发式选择器, 找不到时退化到 body)。 */
    fun extractMainContainer(html: String): String {
        val cleaned = html.replace(COMMENT, "")
        // 语义容器优先 — 与浏览器端 ReaderMode 同序
        for (pattern in listOf(
            Regex("<article[\\s>].*?</article>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            Regex("<main[\\s>].*?</main>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            Regex("<div[^>]*(id|class)\\s*=\\s*[\"'][^\"']*(content|post|article)[^\"']*[\"'][^>]*>.*?</div>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            Regex("<body[\\s>].*?</body>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        )) {
            pattern.find(cleaned)?.let { return it.value }
        }
        return cleaned
    }

    /** 完整流水线: HTML → 正文 → Markdown。 */
    fun convert(html: String, title: String? = null): String {
        val container = stripNoiseTags(extractMainContainer(html))
        return toMarkdown(container, title)
    }

    /** 剥离噪声标签对: script/style/nav/footer/header/aside/iframe/video 及广告/评论容器。 */
    fun stripNoiseTags(html: String): String {
        var out = html
        for (tag in listOf("script", "style", "noscript", "nav", "footer", "header", "aside",
                           "iframe", "video", "audio", "form", "svg", "template")) {
            out = out.replace(
                Regex("(?i)<$tag[\\s>].*?</$tag\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
                ""
            )
        }
        // 广告/评论容器: class/id 含 ad / comment / sidebar / menu / share / related / recommend
        out = out.replace(
            Regex(
                "(?i)<div[^>]*(?:id|class)\\s*=\\s*[\"'][^\"']*(?:ad|ads|comment|sidebar|menu|share|related|recommend)[^\"']*[\"'][^>]*>.*?</div\\s*>",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ),
            ""
        )
        return out
    }

    /** 标签扫描 → Markdown。 */
    fun toMarkdown(html: String, title: String? = null): String {
        val sb = StringBuilder()
        if (!title.isNullOrBlank()) sb.append("# ").append(title.trim()).append("\n\n")

        var pos = 0
        var inPre = false
        var inTable = false
        var inRow = false
        var linkHref: String? = null
        var linkText = StringBuilder()
        var pendingBlank = false

        fun flushLink() {
            val href = linkHref
            if (href != null) {
                val text = linkText.toString().trim()
                if (text.isNotEmpty()) sb.append("[$text]($href)")
                linkHref = null
                linkText = StringBuilder()
            }
        }

        fun newline() {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            pendingBlank = false
        }

        while (true) {
            val m = TAG.find(html, pos) ?: break
            if (m.range.first > pos) {
                val text = decodeEntities(html.substring(pos, m.range.first))
                if (inPre) {
                    sb.append(text)
                } else if (linkHref != null) {
                    linkText.append(text)
                } else {
                    sb.append(text.trim().replace(Regex("\\s+"), " "))
                }
            }
            val tagName = m.groupValues[1].lowercase()
            val isClose = html[m.range.first + 1] == '/'
            val attrs = m.groupValues[2]
            pos = m.range.last + 1

            when (tagName) {
                "br" -> { flushLink(); newline() }
                "p", "div", "section", "blockquote", "figure", "hr", "article", "main", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "table", "ul", "ol", "pre", "code", "a", "img", "td", "th" -> {
                    if (!isClose) {
                        when (tagName) {
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                flushLink(); newline()
                                val n = tagName[1].digitToInt()
                                sb.append("#".repeat(n)).append(' ')
                            }
                            "li" -> { flushLink(); newline(); sb.append("- ") }
                            "p", "div", "section", "blockquote", "figure", "article", "main" -> {
                                flushLink(); newline(); pendingBlank = true
                            }
                            "table" -> { flushLink(); newline(); inTable = true }
                            "tr" -> { flushLink(); if (inRow) newline(); inRow = true }
                            "td", "th" -> {
                                flushLink()
                                sb.append(if (inTable) "| " else "")
                            }
                            "pre" -> { flushLink(); newline(); sb.append("```\n"); inPre = true }
                            "a" -> {
                                linkHref = Regex("href\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.trim()
                            }
                            "img" -> {
                                val src = Regex("src\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.trim()
                                val alt = Regex("alt\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.trim() ?: ""
                                if (src != null) sb.append("![${decodeEntities(alt)}]($src)")
                            }
                            "code" -> if (!inPre) sb.append("`")
                        }
                    } else {
                        when (tagName) {
                            "h1", "h2", "h3", "h4", "h5", "h6" -> newline()
                            "li" -> newline()
                            "p", "div", "section", "blockquote", "figure", "article", "main" -> { newline(); pendingBlank = true }
                            "table" -> { inTable = false; newline() }
                            "tr" -> { inRow = false; newline() }
                            "pre" -> { sb.append("\n```\n"); inPre = false }
                            "a" -> flushLink()
                            "code" -> if (!inPre) sb.append("`")
                            else -> Unit
                        }
                    }
                }
            }
        }

        // 尾部文本
        if (pos < html.length) {
            val text = decodeEntities(html.substring(pos)).trim().replace(Regex("\\s+"), " ")
            if (text.isNotEmpty()) sb.append(text)
        }
        flushLink()

        // 折叠连续空行 (最多 1 个空行)
        return sb.toString()
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .trim()
    }

    /** 常见 HTML 实体解码。 */
    fun decodeEntities(s: String): String {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&nbsp;", " ").replace("&#160;", " ").replace("&ndash;", "-").replace("&mdash;", "—")
    }

    /** 文件名清洗: 只保留安全字符, 空则回退 "page"。 */
    fun sanitizeFileName(name: String): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .take(60)
        return safe.ifBlank { "page" }
    }
}
