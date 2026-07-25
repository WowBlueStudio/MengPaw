// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui

import android.webkit.WebView
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius

/**
 * In-page find bar for MengPaw Browser.
 *
 * Uses Android WebView's [WebView.findAllAsync] and [WebView.findNext] APIs.
 * The FindListener updates the match state reactively.
 */
@Composable
fun BrowserFindBar(
    webView: WebView?,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        var query by remember { mutableStateOf(TextFieldValue("")) }
        var matchInfo by remember { mutableStateOf("0/0") }
        var currentMatch by remember { mutableStateOf(0) }
        var totalMatches by remember { mutableStateOf(0) }
        val focusRequester = remember { FocusRequester() }

        // Set up FindListener on the WebView
        DisposableEffect(webView) {
            val listener = WebView.FindListener { activeMatchOrdinal, numberOfMatches, _ ->
                currentMatch = activeMatchOrdinal
                totalMatches = numberOfMatches
                matchInfo = if (numberOfMatches > 0) "$activeMatchOrdinal/$numberOfMatches" else "0/0"
            }
            webView?.setFindListener(listener)
            onDispose { webView?.setFindListener(null) }
        }

        // Focus the input field when the bar appears
        LaunchedEffect(visible) {
            if (visible) {
                focusRequester.requestFocus()
            }
        }

        // Clear find results when dismissed
        DisposableEffect(visible) {
            onDispose {
                webView?.clearMatches()
                currentMatch = 0
                totalMatches = 0
                matchInfo = "0/0"
            }
        }

        fun doSearch(text: String) {
            if (text.isBlank()) {
                webView?.clearMatches()
                matchInfo = "0/0"
                return
            }
            webView?.findAllAsync(text)
        }

        Surface(
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            color = ThemeColors.bgPrimary,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { q ->
                        query = q
                        doSearch(q.text)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text("在页面中查找...", fontSize = 14.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    shape = RoundedCornerShape(ArcoRadius.round),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ThemeColors.brand,
                        unfocusedBorderColor = ThemeColors.border
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { doSearch(query.text) }
                    )
                )

                Spacer(Modifier.width(8.dp))

                // Match count
                Text(
                    text = matchInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (totalMatches > 0) ThemeColors.brand else ThemeColors.textSecondary,
                    modifier = Modifier.width(44.dp)
                )

                // Previous match
                IconButton(
                    onClick = { webView?.findNext(false) },
                    enabled = totalMatches > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    @Suppress("DEPRECATION")
                    Icon(
                        Icons.Default.ArrowBack,
                        "上一个",
                        tint = if (totalMatches > 0) ThemeColors.textPrimary else ThemeColors.bgCardHigh,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Next match
                IconButton(
                    onClick = { webView?.findNext(true) },
                    enabled = totalMatches > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        "下一个",
                        tint = if (totalMatches > 0) ThemeColors.textPrimary else ThemeColors.bgCardHigh,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Close
                IconButton(
                    onClick = {
                        webView?.clearMatches()
                        onDismiss()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        "关闭查找",
                        tint = ThemeColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
