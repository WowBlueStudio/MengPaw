// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Markdown 预览专用轻量 WebView 工厂 (v0.31.0)。
 *
 * 不复用 WebViewFactory.createWebView — 那是网页浏览专用 (JS bridge / 插件注入 / 广告拦截),
 * 预览只需基础配置 + 导航拦截。加载走 loadDataWithBaseURL(baseUrl=assets/markdown_viewer/)。
 */
fun createMdViewerWebView(context: Context): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    // API 30+ 默认 false — targetSdk 35 下不开则 assets 子资源 (css/js) 加载失败
    settings.allowFileAccess = true
    settings.domStorageEnabled = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
            // md 内 http(s) 链接 → 外部浏览器; 其余 (file:/intent:/javascript:) 一律拦截
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) { }
            }
            return true
        }
    }
}
