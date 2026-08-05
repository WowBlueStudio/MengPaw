// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mengpaw.browser.web.MdViewerHtml
import com.mengpaw.browser.web.createMdViewerWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Markdown viewer dialog — md-reader 观感 WebView 渲染 (v0.31.0)。
 *
 * 原 Compose MarkdownText 改为 assets/markdown_viewer 模板: commonmark → HTML → WebView。
 * 注意: 不用 M3 AlertDialog — 其 text 槽带 verticalScroll 无限高测量, WebView 会被压缩成小方块。
 * HTML 构建在后台线程; >1.2M 字符走 cacheDir 文件回退 (loadDataWithBaseURL 内部 data: URL 有截断风险)。
 */
@Composable
fun BrowserMarkdownViewerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: String
) {
    if (!visible || content.isBlank()) return
    val ctx = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxSize().padding(5.dp)  // 近全屏: 边缘间隙 5dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Markdown 预览", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                var html by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(content) {
                    html = withContext(Dispatchers.Default) {
                        runCatching { MdViewerHtml.render(content, ctx) }.getOrNull()
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val h = html
                    if (h == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        AndroidView(
                            factory = { createMdViewerWebView(it) },
                            update = { wv ->
                                if (wv.tag != h) {
                                    wv.tag = h
                                    loadHtml(wv, h)
                                }
                            },
                            onRelease = { it.destroy() },  // 防 WebView 泄漏
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/** loadDataWithBaseURL; 超大内容 (>1.2M 字符) 回退 cacheDir 文件 + loadUrl。 */
private fun loadHtml(wv: WebView, html: String) {
    val baseUrl = "file:///android_asset/markdown_viewer/"
    if (html.length <= 1_200_000) {
        wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        return
    }
    // data: URL 大内容有设备相关截断 — 写临时文件, 相对资源替换为绝对 asset 路径后 loadUrl
    try {
        val tmp = File(wv.context.cacheDir, "md_viewer_${System.currentTimeMillis()}.html")
        val absolute = html.replace("href=\"viewer.css\"", "href=\"file:///android_asset/markdown_viewer/viewer.css\"")
            .replace("src=\"hljs.min.js\"", "src=\"file:///android_asset/markdown_viewer/hljs.min.js\"")
            .replace("src=\"viewer.js\"", "src=\"file:///android_asset/markdown_viewer/viewer.js\"")
        tmp.writeText(absolute)
        wv.loadUrl("file://${tmp.absolutePath}")
        tmp.deleteOnExit()
    } catch (_: Exception) {
        wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }
}
