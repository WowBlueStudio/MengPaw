// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors

// ── Components ────────────────────────────────────────────────────

@Composable
fun TabChip(label: String, selected: Boolean, isLoading: Boolean, onClick: () -> Unit, onClose: (() -> Unit)?) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ThemeColors.brandContainer else Color.Transparent,
        tonalElevation = if (selected) 1.dp else 0.dp
    ) {
        Row(Modifier.padding(start = 10.dp, end = if (onClose != null) 2.dp else 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = ThemeColors.brand); Spacer(Modifier.width(6.dp)) }
            Text(label.take(20), fontSize = 12.sp, maxLines = 1)
            if (onClose != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, "关闭标签", modifier = Modifier.size(12.dp), tint = ThemeColors.textSecondary)
                }
            }
        }
    }
}
