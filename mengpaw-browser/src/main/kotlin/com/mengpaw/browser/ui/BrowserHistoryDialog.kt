// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.HistoryStore
import com.mengpaw.design.theme.ThemeColors

/** History dialog with browse, clear, and enable/disable toggle. */
@Composable
fun BrowserHistoryDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    historyStore: HistoryStore,
    historyEnabled: Boolean,
    onHistoryEnabledToggle: (Boolean) -> Unit,
    onNavigate: (String) -> Unit
) {
    if (!visible) return

    val entries = remember { historyStore.all() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("历史记录 History")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("记录", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                    Switch(checked = historyEnabled, onCheckedChange = onHistoryEnabledToggle)
                }
            }
        },
        text = {
            LazyColumn {
                if (entries.isEmpty()) {
                    item { Text("暂无历史记录", color = ThemeColors.textSecondary, modifier = Modifier.padding(16.dp)) }
                }
                items(entries.take(50)) { entry ->
                    Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onNavigate(entry.url); onDismiss() },
                        shape = RoundedCornerShape(6.dp), color = ThemeColors.bgCardHigh) {
                        Column(Modifier.padding(8.dp)) {
                            Text(entry.title.ifBlank { entry.url.take(50) }, maxLines = 1, fontSize = 13.sp)
                            Row { Text(entry.url.take(60), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f)); Text(entry.countdown, style = MaterialTheme.typography.labelSmall, color = if (entry.daysLeft < 3) ThemeColors.error else ThemeColors.textSecondary) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { historyStore.clear(); onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = ThemeColors.error)) {
                Text("清空全部")
            }
        }
    )
}
