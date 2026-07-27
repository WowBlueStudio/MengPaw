// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui

import android.content.Intent
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.TabState
import com.mengpaw.design.theme.ThemeColors

/** Phone tab management dialog: favicon + title/URL + close + per-tab menu. */
@Composable
fun BrowserTabDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    tabs: List<TabState>,
    activeTabId: Int,
    webViewMap: Map<Int, WebView>,
    prefs: BrowserPrefs,
    onTabSelected: (Int, Boolean) -> Unit,  // (tabId, isColdStart)
    onTabClose: (Int) -> Unit,
    onNewTab: () -> Unit,
    maxTabs: Int
) {
    if (!visible) return

    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("标签页 (${tabs.size})")
                if (tabs.size < maxTabs) {
                    IconButton(onClick = onNewTab) {
                        Icon(Icons.Default.Add, "新建标签", tint = ThemeColors.brand)
                    }
                }
            }
        },
        text = {
            Column {
                tabs.forEach { tab ->
                    var showMenu by remember { mutableStateOf(false) }
                    val isActive = tab.id == activeTabId
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onTabSelected(tab.id, tab.url.isBlank()) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isActive) ThemeColors.brandContainer.copy(alpha = 0.5f) else ThemeColors.bgCardHigh
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tab.title.ifBlank { "新标签页" }, maxLines = 1, fontSize = 13.sp, fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else null)
                                Text(tab.url.take(60).ifBlank { "about:blank" }, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            // Close button
                            if (tabs.size > 1) {
                                IconButton(onClick = { onTabClose(tab.id) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                                }
                            }
                            // Menu
                            Box {
                                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.MoreHoriz, "菜单", modifier = Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(text = { Text("推送给智能体") }, onClick = {
                                        val intent = Intent("com.mengpaw.action.OPEN_URL").apply {
                                            setClassName("com.mengpaw.shell", "com.mengpaw.shell.MainActivity")
                                            putExtra("url", tab.url)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        }
                                        try { ctx.startActivity(intent) } catch (_: Exception) { }
                                        showMenu = false
                                    })
                                    DropdownMenuItem(text = { Text("强制刷新") }, onClick = { webViewMap[tab.id]?.reload(); showMenu = false })
                                    DropdownMenuItem(text = { Text("添加收藏") }, onClick = { prefs.addBookmark(tab.url); showMenu = false })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
