// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.content.Context
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Markdown → HTML 渲染管线 (md-reader 观感 WebView 预览, v0.31.0)。
 *
 * 流程: commonmark 解析 (GFM tables/strikethrough) → HtmlRenderer 转义输出
 * → 注入 assets/markdown_viewer/viewer.html 模板。Parser/Renderer 线程安全, 单例复用;
 * 调用方在 Dispatchers.Default 执行, 避免主线程卡顿。
 *
 * 安全: escapeHtml=true (md 内 <script> 原样转义显示) + sanitizeUrls=true (javascript: 链接被滤)。
 * 模板替换标记用 HTML 注释 — md 经转义后不可能产生 "<!--", 无碰撞风险 (花括号标记会被真实文档撞车)。
 */
object MdViewerHtml {

    private const val TEMPLATE_PATH = "markdown_viewer/viewer.html"
    private const val BODY_MARKER = "<!--__MENGPAW_MD_BODY__-->"

    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .softbreak("\n")
        .build()

    /** 渲染完整 HTML 文档 (在后台线程调用)。md 为空返回 null。 */
    fun render(md: String, context: Context): String? {
        if (md.isBlank()) return null
        val body = renderer.render(parser.parse(md))
        val template = context.assets.open(TEMPLATE_PATH).readBytes().decodeToString()
        return template.replace(BODY_MARKER, body)
    }
}
