// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.design.theme.ThemeColors

/** Bookmark dialog with add current URL and click-to-navigate. */
@Composable
fun BrowserBookmarkDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    prefs: BrowserPrefs,
    currentUrl: String,
    onNavigate: (String) -> Unit
) {
    if (!visible) return

    val list = prefs.bookmarks

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("收藏夹 (${list.size})")
                if (currentUrl.isNotBlank()) {
                    IconButton(onClick = { prefs.addBookmark(currentUrl) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Add, "添加当前页", tint = ThemeColors.brand)
                    }
                }
            }
        },
        text = {
            LazyColumn {
                if (list.isEmpty()) {
                    item { Text("暂无收藏", color = ThemeColors.textSecondary, modifier = Modifier.padding(16.dp)) }
                }
                items(list) { url ->
                    Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onNavigate(url); onDismiss() },
                        shape = RoundedCornerShape(6.dp), color = ThemeColors.bgCardHigh) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(url.substringAfter("://").substringBefore("/").take(30).ifBlank { url.take(40) }, maxLines = 1, fontSize = 13.sp)
                                Text(url.take(60), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            IconButton(onClick = { prefs.removeBookmark(url) }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, "删除", modifier = Modifier.size(14.dp), tint = ThemeColors.textSecondary)
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
