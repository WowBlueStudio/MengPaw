// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.ui.components.SearchEngineLogo
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius

/**
 * Cold-start new tab page with branding, search/URL input, and a dynamic bookmark bar.
 */
@Composable
fun NewTabPage(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchEngine: SearchEngine,
    onSearchEngineCycle: () -> Unit,
    isWide: Boolean,
    prefs: BrowserPrefs,
    onNavigate: (String) -> Unit,
    onShowBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top spacer
        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "MengPaw 浏览器",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = ThemeColors.textPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "安全的 Agent 控制浏览器",
                    style = MaterialTheme.typography.bodySmall,
                    color = ThemeColors.textSecondary
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        // Search / URL input bar
        Surface(
            modifier = Modifier.fillMaxWidth(if (isWide) 0.55f else 0.88f),
            shape = RoundedCornerShape(ArcoRadius.round),
            shadowElevation = 2.dp,
            color = ThemeColors.surface
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_TAB
                            && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        ) {
                            onSearchEngineCycle()
                            true
                        } else false
                    },
                placeholder = { Text("搜索关键词或输入网址...") },
                leadingIcon = {
                    Box(Modifier.pointerInput(Unit) { detectTapGestures {
                        onSearchEngineCycle()
                    }}) {
                        Box(Modifier.offset(x = 2.dp)) {
                            SearchEngineLogo(searchEngine, size = 28)
                        }
                    }
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty())
                        FilledIconButton(
                            onClick = { onNavigate(searchQuery) },
                            modifier = Modifier.size(36.dp).offset(x = (-2).dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = ThemeColors.brand
                            )
                        ) {
                            Icon(
                                Icons.Default.ArrowForward,
                                "→",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                },
                singleLine = true,
                shape = RoundedCornerShape(ArcoRadius.round),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onNavigate(searchQuery) }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ThemeColors.brand,
                    unfocusedBorderColor = ThemeColors.brand.copy(alpha = 0.2f)
                )
            )
        }
        // Dynamic bookmark bar
        val bmList = prefs.bookmarks
        if (bmList.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(if (isWide) 0.55f else 0.88f)
            ) {
                val itemWidth = 72.dp
                val maxItems = (maxWidth / itemWidth).toInt().coerceAtLeast(1).coerceAtMost(6)
                val showOverflow = bmList.size > maxItems
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bmList.take(if (showOverflow) maxItems - 1 else maxItems).forEach { url ->
                        val domain = url.substringAfter("://").substringBefore("/").take(10)
                        Surface(
                            modifier = Modifier.weight(1f).clickable { onNavigate(url) },
                            shape = RoundedCornerShape(10.dp),
                            color = ThemeColors.bgCardHigh
                        ) {
                            Text(
                                domain,
                                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                fontSize = 11.sp,
                                color = ThemeColors.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                    if (showOverflow) {
                        Surface(
                            modifier = Modifier.weight(1f).clickable { onShowBookmarks() },
                            shape = RoundedCornerShape(10.dp),
                            color = ThemeColors.brand.copy(alpha = 0.1f)
                        ) {
                            Text(
                                "…",
                                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                                color = ThemeColors.brand,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        // Bottom balance spacer
        Box(Modifier.weight(1f))
    }
}
