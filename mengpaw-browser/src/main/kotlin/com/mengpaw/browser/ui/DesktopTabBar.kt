// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.content.Intent
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.ui.components.TabChip
import com.mengpaw.design.theme.ThemeColors

/**
 * Desktop-style tab bar displayed on wide screens (>=600dp).
 * Shows a horizontal row of [TabChip] items with a seam line below.
 */
@Composable
fun DesktopTabBar(
    tabs: List<TabState>,
    activeTabId: Int,
    maxTabs: Int,
    webViewMap: MutableMap<Int, WebView>,
    prefs: BrowserPrefs,
    onTabSelected: (Int) -> Unit,
    onTabClose: (Int) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    Surface(tonalElevation = 1.dp, color = ThemeColors.bgCardHigh) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                var tabMenuTabId by remember { mutableStateOf<Int?>(null) }
                tabs.forEach { tab ->
                    TabChip(
                        label = tab.title.ifBlank { "新标签页" },
                        selected = tab.id == activeTabId,
                        isLoading = tab.isLoading,
                        onClick = { onTabSelected(tab.id) },
                        onClose = if (tabs.size > 1) {{
                            onTabClose(tab.id)
                        }} else null,
                        onMenu = { tabMenuTabId = tab.id }
                    )
                    // Per-tab dropdown menu
                    DropdownMenu(
                        expanded = tabMenuTabId == tab.id,
                        onDismissRequest = { tabMenuTabId = null }
                    ) {
                        DropdownMenuItem(
                            text = { Text("静音标签") },
                            onClick = { tabMenuTabId = null }
                        )
                        DropdownMenuItem(
                            text = { Text("推送给智能体") },
                            onClick = {
                                val intent = Intent("com.mengpaw.action.OPEN_URL").apply {
                                    setClassName(
                                        "com.mengpaw.shell",
                                        "com.mengpaw.shell.MainActivity"
                                    )
                                    putExtra("url", tab.url)
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    )
                                }
                                try {
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {
                                }
                                tabMenuTabId = null
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("强制刷新") },
                            onClick = {
                                webViewMap[tab.id]?.reload()
                                tabMenuTabId = null
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("添加收藏") },
                            onClick = {
                                prefs.addBookmark(tab.url)
                                tabMenuTabId = null
                            }
                        )
                    }
                }
                if (tabs.size < maxTabs) {
                    IconButton(
                        onClick = onNewTab,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            "新标签",
                            tint = ThemeColors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            // Seam line below tabs — same color as active tab, bridges to webpage
            val seamColor = if (isSystemInDarkTheme()) Color(0xFF1A1A1A) else Color.White
            Box(Modifier.fillMaxWidth().height(2.dp).background(seamColor))
        }
    }
}
