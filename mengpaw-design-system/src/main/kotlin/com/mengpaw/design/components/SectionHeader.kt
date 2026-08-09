// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors

/**
 * Shared section header for settings screens and sidebars.
 * Renders a bold title in the brand primary color.
 *
 * 传入 [expanded] 与 [onToggle] 时渲染为可点击折叠头（标题 + 计数 + chevron），
 * 用于"默认折叠"的列表区块；不传则保持纯标题（向后兼容）。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null
) {
    if (expanded == null || onToggle == null) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            color = ThemeColors.accentText,
            modifier = modifier
        )
        return
    }
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            color = ThemeColors.accentText,
            modifier = Modifier.weight(1f)
        )
        if (count != null) {
            Text(count, fontSize = 12.sp, color = ThemeColors.textSecondary)
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = ThemeColors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
