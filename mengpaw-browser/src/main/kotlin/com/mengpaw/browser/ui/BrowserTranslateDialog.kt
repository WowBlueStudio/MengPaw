// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.service.GoogleTranslate
import com.mengpaw.design.theme.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Translate dialog: language picker, JS text extraction, Google Translate call. */
@Composable
fun BrowserTranslateDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    activeTab: TabState,
    webView: WebView?
) {
    if (!visible) return

    val targetLang = remember { mutableStateOf("zh-CN") }
    val translating = remember { mutableStateOf(false) }
    val result = remember { mutableStateOf("") }
    val sysLang = java.util.Locale.getDefault().language.let {
        when (it) { "zh" -> "zh-CN"; "en" -> "en"; "ja" -> "ja"; "ko" -> "ko"; else -> "zh-CN" }
    }
    LaunchedEffect(visible) { targetLang.value = sysLang }

    AlertDialog(
        onDismissRequest = { onDismiss(); result.value = "" },
        title = { Text("翻译页面") },
        text = {
            Column {
                Text(activeTab.title.ifBlank { activeTab.url.take(60) }, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(12.dp))
                // Language picker
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("翻译为:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(GoogleTranslate.LANGUAGES.entries.find { it.value == targetLang.value }?.key ?: targetLang.value, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            GoogleTranslate.LANGUAGES.forEach { (name, code) ->
                                DropdownMenuItem(text = { Text(name) },
                                    onClick = { targetLang.value = code; expanded = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Translate button
                val translateScope = rememberCoroutineScope()
                Button(
                    onClick = {
                        translating.value = true
                        translateScope.launch(Dispatchers.IO) {
                            try {
                                // Extract page body text via JS injection
                                val pageText = withContext(Dispatchers.Main) {
                                    suspendCancellableCoroutine { cont ->
                                        val wv = webView
                                        if (wv == null) {
                                            cont.resume(activeTab.title) {}
                                            return@suspendCancellableCoroutine
                                        }
                                        wv.evaluateJavascript(
                                            """(function(){var b=document.body;return b?b.innerText||b.textContent||"":""})()"""
                                        ) { jsResult ->
                                            val raw = jsResult?.trim()?.removeSurrounding("\"") ?: ""
                                            val unescaped = raw
                                                .replace("\\\"", "\"")
                                                .replace("\\n", "\n")
                                                .replace("\\\\", "\\")
                                            val text = unescaped.take(5000)
                                            cont.resume(text.ifBlank { activeTab.title }) {}
                                        }
                                    }
                                }
                                val translated = GoogleTranslate.translate(pageText, targetLang.value)
                                withContext(Dispatchers.Main) { result.value = translated }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { result.value = "翻译失败: ${e.message}" }
                            }
                            translating.value = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !translating.value
                ) {
                    if (translating.value) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (translating.value) "翻译中..." else "翻译")
                }
                if (result.value.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(color = ThemeColors.bgCardHigh, shape = RoundedCornerShape(8.dp)) {
                        Text(result.value, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); result.value = "" }) { Text("关闭") } }
    )
}
