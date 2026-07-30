// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

@Composable
fun SettingsSidebar(
    strings: AppStrings,
    selected: Int,
    onSelect: (Int) -> Unit,
    expanded: Boolean
) {
    Column(
        Modifier
            .width(if (expanded) 240.dp else 68.dp)
            .fillMaxHeight()
            .background(ThemeColors.bgSecondary)
            .padding(top = ArcoSpacing.md)
    ) {
        SidebarItem(
            number = "01",
            title = strings.sidebarSettingsAgent,
            subtitle = null,
            selected = selected == 0,
            expanded = expanded,
            containerColor = if (selected == 0) ThemeColors.brandContainer else Color.Transparent,
            onClick = { onSelect(0) }
        )
        SidebarItem(
            number = "02",
            title = strings.sidebarSettingsFramework,
            subtitle = null,
            selected = selected == 1,
            expanded = expanded,
            onClick = { onSelect(1) }
        )
        SidebarItem(
            number = "03",
            title = strings.sidebarSettingsSystem,
            subtitle = null,
            selected = selected == 2,
            expanded = expanded,
            onClick = { onSelect(2) }
        )
    }
}

@Composable
fun SidebarItem(
    number: String,
    title: String,
    subtitle: String?,
    selected: Boolean,
    expanded: Boolean,
    containerColor: Color = Color.Transparent,
    onClick: () -> Unit
) {
    val bgColor = if (containerColor != Color.Transparent && selected) containerColor
        else if (selected) ThemeColors.bgCardHigh
        else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (expanded) ArcoSpacing.sm else 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(ArcoRadius.md))
            .clickable { onClick() },
        color = bgColor,
        shape = RoundedCornerShape(ArcoRadius.md)
    ) {
        if (expanded) {
            Row(
                Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    number,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) ThemeColors.brand else ThemeColors.textSecondary
                )
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                        color = if (selected) ThemeColors.textPrimary else ThemeColors.textSecondary
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            fontSize = 11.sp,
                            color = ThemeColors.textSecondary,
                            maxLines = 1
                        )
                    }
                }
                if (selected) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        Modifier.size(16.dp),
                        tint = ThemeColors.brand
                    )
                }
            }
        } else {
            Column(
                Modifier.padding(vertical = ArcoSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    number,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) ThemeColors.brand else ThemeColors.textSecondary
                )
                Spacer(Modifier.height(2.dp))
                Icon(
                    when (number) {
                        "01" -> Icons.Outlined.SmartToy
                        "02" -> Icons.Outlined.Hub
                        else -> Icons.Outlined.Tune
                    },
                    null,
                    Modifier.size(20.dp),
                    tint = if (selected) ThemeColors.brand else ThemeColors.textSecondary
                )
            }
        }
    }
}
