// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors

/** Tab-style chip: top-rounded, white/surface-bg when active, bottom flat. */
@Composable
fun TabChip(
    label: String, selected: Boolean, isLoading: Boolean,
    onClick: () -> Unit, onClose: (() -> Unit)?,
    onMenu: (() -> Unit)? = null
) {
    val bg = when {
        !selected -> Color.Transparent
        isSystemInDarkTheme() -> Color(0xFF1A1A1A)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp, 8.dp, 0.dp, 0.dp))
            .background(bg)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = ThemeColors.brand); Spacer(Modifier.width(6.dp)) }
            Text(label.take(16), fontSize = 12.sp, maxLines = 1,
                color = if (selected) ThemeColors.textPrimary else ThemeColors.textSecondary)
            if (onMenu != null) {
                IconButton(onClick = onMenu, modifier = Modifier.size(16.dp)) {
                    Icon(Icons.Default.MoreHoriz, "菜单", modifier = Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                }
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onClose ?: onClick, modifier = Modifier.size(16.dp)) {
                Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(12.dp), tint = ThemeColors.textSecondary)
            }
        }
    }
}
