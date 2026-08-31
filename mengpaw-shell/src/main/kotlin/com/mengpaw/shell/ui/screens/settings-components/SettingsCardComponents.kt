// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.design.components.SectionHeader

@Composable
fun StatCard(
    title: String, value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bgColor: Color, fgColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = bgColor, shape = RoundedCornerShape(ArcoRadius.sm)) {
        Column(Modifier.padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.md)) {
            Icon(icon, null, Modifier.size(18.dp), tint = fgColor)
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = fgColor, maxLines = 1)
            Text(title, fontSize = 10.sp, color = fgColor.copy(alpha = 0.7f), maxLines = 2)
        }
    }
}

@Composable
fun FrameworkItemSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<FrameworkItem>,
    strings: AppStrings
) {
    if (title.isNotBlank()) SectionHeader(title)
    if (items.isEmpty()) {
        Text(strings.noEntries, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
        return
    }

    val order = listOf(ItemCategory.BUILTIN, ItemCategory.OFFICIAL, ItemCategory.CUSTOM)
    val grouped = items.groupBy { it.category }

    order.forEach { cat ->
        val group = grouped[cat] ?: return@forEach
        group.forEach { item ->
            key(item.name) {
                var expanded by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard, onClick = { expanded = !expanded }
                ) {
                    Column(Modifier.padding(ArcoSpacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(16.dp), tint = cat.color)
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                                if (item.summary.isNotBlank()) Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            // Category badge
                            Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = cat.color.copy(alpha = 0.1f)) {
                                Text(if (strings.isChinese) cat.label else cat.enLabel,
                                    Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = cat.color)
                            }
                            // WowBlue badge for built-in features
                            if (item.isWowBlue) {
                                Spacer(Modifier.width(4.dp))
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Pink6.copy(alpha = 0.1f)) {
                                    Text("WowBlue", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = ArcoColors.Pink6)
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                        }
                        AnimatedVisibility(visible = expanded) {
                            if (item.docMarkdown.isNotBlank()) {
                                // 全文展开（外层页面滚动查看）— nestedScroll=true 避免自带 verticalScroll 在
                                // 双层 AnimatedVisibility 下拿到无限高度约束而崩溃（此前 heightIn(300) 会裁切）
                                MarkdownText(content = item.docMarkdown.take(5000), modifier = Modifier.padding(top = ArcoSpacing.sm),
                                    nestedScroll = true)
                            } else {
                                Text(strings.noDocs, Modifier.padding(top = ArcoSpacing.sm), fontSize = 12.sp, color = ThemeColors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(ArcoSpacing.sm))
}
