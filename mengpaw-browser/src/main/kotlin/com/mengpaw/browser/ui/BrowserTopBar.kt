// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.plugin.BrowserPluginRegistry
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius

/**
 * Browser top app bar with animated visibility.
 *
 * Contains: URL bar (editable or display-only), navigation icons (home/back/forward/reload
 * on wide screens, tab-badge on phone), bookmark star, and the overflow menu.
 *
 * All state mutations are delegated via callbacks so the parent remains the owner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTopBar(
    visible: Boolean,
    showUrlBar: Boolean,
    onShowUrlBarChange: (Boolean) -> Unit,
    isWide: Boolean,
    activeTab: TabState,
    activeTabId: Int,
    tabs: List<TabState>,
    adBlockEnabled: Boolean,
    isBookmarked: Boolean,
    webViewMap: MutableMap<Int, WebView>,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onShowTabs: () -> Unit,
    onShowBookmarks: () -> Unit,
    onRefresh: () -> Unit,
    onGoForward: () -> Unit,
    onGoBack: () -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: () -> Unit,
    onShowTranslate: () -> Unit,
    onShowFind: () -> Unit,
    onShowReader: () -> Unit,
    onAdBlockToggle: () -> Unit,
    onShowHistory: () -> Unit,
    onShowPasswords: () -> Unit,
    onShare: (String) -> Unit,
    onSendToAgent: (String) -> Unit,
    onExtractToAgent: (String, String) -> Unit,
    onShowSettings: () -> Unit,
    onShowAgentSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(animationSpec = tween(200)),
        exit = fadeOut() + slideOutVertically(animationSpec = tween(200)),
        modifier = modifier
    ) {
        TopAppBar(
            title = {
                if (showUrlBar || isWide) {
                    var editUrl by remember(activeTabId) { mutableStateOf(activeTab.url) }
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                        shape = RoundedCornerShape(ArcoRadius.round),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ThemeColors.brand,
                            unfocusedBorderColor = ThemeColors.border
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { onNavigate(editUrl) }
                        ),
                        trailingIcon = {
                            FilledIconButton(
                                onClick = { onNavigate(editUrl) },
                                modifier = Modifier.size(32.dp).offset(x = 1.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = ThemeColors.brand
                                )
                            ) {
                                Icon(
                                    Icons.Default.ArrowForward,
                                    "→",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { onShowUrlBarChange(true) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeTab.title.isNotBlank()) Column(Modifier.weight(1f)) {
                            Text(
                                activeTab.title,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                activeTab.url.take(60),
                                style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                Row {
                    if (isWide) {
                        // Visible nav buttons for keyboard+mouse on tablet
                        IconButton(
                            onClick = { onNavigate("https://www.baidu.com") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Home, "主页", tint = ThemeColors.brand)
                        }
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(36.dp)
                        ) {
                            @Suppress("DEPRECATION")
                            Icon(
                                Icons.Default.ArrowBack,
                                "后退",
                                tint = if (activeTab.canGoBack) ThemeColors.brand
                                else ThemeColors.brand.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = onGoForward,
                            enabled = activeTab.canGoForward,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowForward,
                                "前进",
                                tint = if (activeTab.canGoForward) ThemeColors.brand
                                else ThemeColors.brand.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Refresh, "刷新", tint = ThemeColors.brand)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    // Tab count badge (phone only, hidden when 1 tab)
                    if (!isWide) {
                        IconButton(
                            onClick = onShowTabs,
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (tabs.size > 1) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = ThemeColors.textSecondary) {
                                            Text("${tabs.size}")
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.List, "标签页", tint = ThemeColors.brand)
                                }
                            } else {
                                Icon(Icons.Default.List, "标签页", tint = ThemeColors.brand)
                            }
                        }
                    }
                }
            },
            actions = {
                // Bookmark star
                IconButton(
                    onClick = onShowBookmarks,
                    modifier = Modifier.size(36.dp)
                ) {
                    @Suppress("DEPRECATION")
                    Icon(
                        Icons.Default.Star,
                        "收藏夹",
                        tint = if (isBookmarked) ThemeColors.brand else ThemeColors.textSecondary
                    )
                }
                // Menu button with dropdown
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, "菜单", tint = ThemeColors.brand)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { onRefresh(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("新标签页") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { onNewTab(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("翻译页面") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            enabled = activeTab.title.isNotBlank(),
                            onClick = { onShowTranslate(); menuExpanded = false }
                        )
                        @Suppress("DEPRECATION")
                        DropdownMenuItem(
                            text = { Text("页面查找") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            enabled = activeTab.title.isNotBlank(),
                            onClick = { onShowFind(); menuExpanded = false }
                        )
                        @Suppress("DEPRECATION")
                        DropdownMenuItem(
                            text = { Text("阅读模式") },
                            leadingIcon = { Icon(Icons.Default.Star, null) },
                            enabled = activeTab.title.isNotBlank(),
                            onClick = { onShowReader(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (adBlockEnabled) "广告拦截: 开" else "广告拦截: 关"
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (adBlockEnabled) Icons.Default.Star
                                    else Icons.Default.Close,
                                    null
                                )
                            },
                            onClick = { onAdBlockToggle(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("后退") },
                            leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                            enabled = activeTab.canGoBack,
                            onClick = { onGoBack(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("前进") },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                            enabled = activeTab.canGoForward,
                            onClick = { onGoForward(); menuExpanded = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("历史记录") },
                            leadingIcon = { Icon(Icons.Default.Star, null) },
                            onClick = { onShowHistory(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("密码管理") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            onClick = { onShowPasswords(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("分享链接") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { onShare(activeTab.url); menuExpanded = false }
                        )
                        if (ctx.packageManager.getLaunchIntentForPackage("com.mengpaw.shell") != null) {
                            DropdownMenuItem(
                                text = { Text("发送给 MengPaw Agent") },
                                leadingIcon = { Icon(Icons.Default.Send, null) },
                                onClick = {
                                    onSendToAgent(activeTab.url)
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("提炼网页要点") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                onClick = {
                                    onExtractToAgent(activeTab.url, activeTab.title)
                                    menuExpanded = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("设置") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { onShowSettings(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("智能体协同") },
                            leadingIcon = { Icon(Icons.Default.SmartToy, null) },
                            onClick = { onShowAgentSettings(); menuExpanded = false }
                        )
                        // Plugin-contributed menu items
                        val pluginItems = remember { BrowserPluginRegistry.activeMenuItems() }
                        if (pluginItems.isNotEmpty()) {
                            HorizontalDivider()
                            pluginItems.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    leadingIcon = { Icon(Icons.Default.Star, null) },
                                    onClick = {
                                        item.command.let { cmd ->
                                            webViewMap[activeTabId]?.evaluateJavascript(
                                                cmd,
                                                null
                                            )
                                        }
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("关闭标签 Close Tab") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            enabled = tabs.size > 1,
                            onClick = { onCloseTab(); menuExpanded = false }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }
}
