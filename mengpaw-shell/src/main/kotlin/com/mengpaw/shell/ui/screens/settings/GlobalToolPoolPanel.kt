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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * 全局工具池面板 — 命令按命名空间分组折叠 (v0.34.1)。
 *
 * 数据源: CommandSearch 索引全量 (engine.listCommands), 随注册表自动更新。
 * 分组: 内核命名空间 → 核心 (蓝), 插件命名空间 → 插件 (橙); 组头默认折叠,
 * 点击展开该命名空间命令; 命令行内再展开查看完整描述。
 * 命令不可删除/不可指定 (无文件实体, 与技能面板不同)。
 */
@Composable
internal fun GlobalToolPoolPanel(
    items: List<FrameworkItem>,
    strings: AppStrings
) {
    if (items.isEmpty()) {
        Text(strings.noEntries, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
        return
    }

    // 按命名空间分组; 排序: 核心在前, 组内字母序
    val groups = items.groupBy { it.name.substringBefore(".") }
        .toSortedMap()
        .entries
        .sortedWith(compareBy({ toolSourceFor(it.key) != "core" }, { it.key }))

    groups.forEach { (ns, cmds) ->
        val source = toolSourceFor(ns)
        key(ns) {
            var expanded by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clickable { expanded = !expanded },
                shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard
            ) {
                Column(Modifier.padding(ArcoSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp),
                            tint = if (source == "core") ArcoColors.Blue6 else ArcoColors.Orange6)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ns, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = ThemeColors.textPrimary)
                                Text(" (${cmds.size})", fontSize = 11.sp, color = ThemeColors.textSecondary)
                            }
                        }
                        // 来源徽标 — 核心/插件 (与技能面板同语义同配色)
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                            color = if (source == "core") ArcoColors.Blue6.copy(alpha = 0.1f) else ArcoColors.Orange6.copy(alpha = 0.1f)) {
                            Text(
                                if (source == "core") strings.skillSourceCore else strings.skillSourcePlugin,
                                Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                fontSize = 10.sp,
                                color = if (source == "core") ArcoColors.Blue6 else ArcoColors.Orange6
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(Modifier.padding(start = ArcoSpacing.md, top = ArcoSpacing.xs)) {
                            cmds.forEach { item ->
                                key(item.name) {
                                    var cmdExpanded by remember { mutableStateOf(false) }
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        shape = RoundedCornerShape(ArcoRadius.sm),
                                        color = ThemeColors.bgCardHigh,
                                        onClick = { cmdExpanded = !cmdExpanded }
                                    ) {
                                        Column(Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(item.name, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                                        color = ThemeColors.textPrimary)
                                                    if (item.summary.isNotBlank())
                                                        Text(item.summary, fontSize = 11.sp, color = ThemeColors.textSecondary, maxLines = 1)
                                                }
                                                Icon(if (cmdExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                    null, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                                            }
                                            AnimatedVisibility(visible = cmdExpanded) {
                                                // 展开显示完整释义 (副标题已精简, 全文在 docMarkdown)
                                                val fullText = item.docMarkdown.ifBlank { item.summary }
                                                if (fullText.isNotBlank()) {
                                                    Text(fullText, Modifier.padding(top = ArcoSpacing.xs), fontSize = 12.sp,
                                                        color = ThemeColors.textSecondary, lineHeight = 20.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(ArcoSpacing.sm))
}
