// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Reader mode — extracts and displays page content with clean typography.
 *
 * Injects JS to extract the main content (prioritising semantic elements:
 * `<article>` → `#content` → `.post` → `body`), then renders it in a
 * scrollable dialog with large text and wide line spacing for comfortable reading.
 */
@Composable
fun BrowserReaderMode(
    webView: WebView?,
    pageTitle: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Extract content when dialog opens
    LaunchedEffect(visible) {
        if (webView == null) {
            error = "浏览器未就绪"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val extracted = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    webView.evaluateJavascript("""
(function(){
    try {
        // Prefer semantic content containers, fall back to body
        var el = document.querySelector('article')
            || document.querySelector('[role=main]')
            || document.querySelector('#content')
            || document.querySelector('.post')
            || document.querySelector('.article')
            || document.querySelector('main')
            || document.body;
        if (!el) return JSON.stringify({text:'',title:document.title});

        // Clone to avoid mutating the live DOM
        var clone = el.cloneNode(true);

        // Remove non-content elements
        var remove = clone.querySelectorAll('script,style,nav,footer,header,.sidebar,.ad,.ads,.advertisement,.nav,.navbar,.menu,.footer,.header,.comment,.comments,#comments,.related,.recommended,.social-share,.share-buttons,iframe,video,audio,img[src*="ad"],.ad-container,.ad-wrapper,[class*=ad-],[class*=ads-]');
        remove.forEach(function(n){ n.remove(); });

        var text = (clone.innerText||clone.textContent||'')
            .replace(/[\\n\\r]{3,}/g,'\\n\\n')  // collapse multiple blank lines
            .replace(/[ \\t]{2,}/g,' ')          // collapse multiple spaces
            .trim();

        var title = document.title||'';
        // Extract headings for structure
        var headings = [];
        document.querySelectorAll('h1,h2,h3').forEach(function(h){
            headings.push({tag:h.tagName,text:(h.textContent||'').trim().substring(0,200)});
        });

        return JSON.stringify({text:text,title:title,headings:headings});
    } catch(e) { return JSON.stringify({error:e.message}); }
})()""".trimIndent()) { result ->
                        val raw = result?.trim()?.removeSurrounding("\"") ?: "{}"
                        try {
                            val json = org.json.JSONObject(
                                raw.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
                            )
                            if (json.has("error")) {
                                cont.resume(json.getString("error")) { error = "提取失败: ${json.getString("error")}" }
                            } else {
                                val title = json.optString("title", pageTitle)
                                val text = json.optString("text", "")
                                cont.resume("# $title\n\n$text") {}
                            }
                        } catch (e: Exception) {
                            cont.resume(raw.take(5000)) {}
                        }
                    }
                }
            }
            content = extracted
        } catch (e: Exception) {
            error = "提取失败: ${e.message}"
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("阅读模式", fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        text = {
            Column {
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ThemeColors.brand)
                    }
                } else if (error != null) {
                    Text(error!!, color = ThemeColors.error, modifier = Modifier.padding(16.dp))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        // Title
                        Text(
                            text = pageTitle.ifBlank { "阅读模式" },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = ThemeColors.textPrimary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        HorizontalDivider(color = ThemeColors.border, modifier = Modifier.padding(bottom = 16.dp))
                        // Body content
                        Text(
                            text = content.ifBlank { "未能提取到页面内容" },
                            fontSize = 16.sp,
                            lineHeight = 28.sp,
                            color = ThemeColors.textPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
