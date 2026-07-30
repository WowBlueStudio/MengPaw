// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    bgColor: Color, fgColor: Color
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = bgColor) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Icon(icon, null, Modifier.size(20.dp), tint = fgColor)
            Spacer(Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = fgColor)
            Text(title, fontSize = 11.sp, color = fgColor.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun FrameworkItemSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<FrameworkItem>
) {
    if (title.isNotBlank()) SectionHeader(title)
    if (items.isEmpty()) {
        Text("暂无条目", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
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
                                Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                                if (item.summary.isNotBlank()) Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = cat.color.copy(alpha = 0.1f)) {
                                Text(cat.label, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = cat.color)
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                        }
                        AnimatedVisibility(visible = expanded) {
                            if (item.docMarkdown.isNotBlank()) {
                                MarkdownText(content = item.docMarkdown.take(5000), modifier = Modifier.padding(top = ArcoSpacing.sm).heightIn(max = 300.dp))
                            } else {
                                Text("暂无文档", Modifier.padding(top = ArcoSpacing.sm), fontSize = 12.sp, color = ThemeColors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(ArcoSpacing.sm))
}

@Composable
fun AgentItemsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    agentItems: List<FrameworkItem>,
    globalPoolItems: List<FrameworkItem>,
    globalPoolLabel: String,
    installHelp: String
) {
    val loadedItems = remember { mutableStateListOf<FrameworkItem>().also { it.addAll(agentItems) } }
    var showInstallDialog by remember { mutableStateOf(false) }

    SectionHeader(title)
    Text(installHelp, fontSize = 11.sp, color = ThemeColors.textSecondary, lineHeight = 16.sp, modifier = Modifier.padding(bottom = ArcoSpacing.xs))

    TextButton(onClick = { showInstallDialog = true }, contentPadding = PaddingValues(horizontal = ArcoSpacing.sm, vertical = 2.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(15.dp), tint = ThemeColors.brand)
        Spacer(Modifier.width(4.dp))
        Text(globalPoolLabel, fontSize = 13.sp, color = ThemeColors.brand)
    }

    Spacer(Modifier.height(ArcoSpacing.sm))

    if (loadedItems.isEmpty()) {
        Text("暂未加载任何项 — 点击上方按钮从全局池安装", fontSize = 12.sp, color = ThemeColors.textSecondary, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
    } else {
        val order = listOf(ItemCategory.BUILTIN, ItemCategory.OFFICIAL, ItemCategory.CUSTOM)
        val grouped = loadedItems.groupBy { it.category }
        order.forEach { cat ->
            val group = grouped[cat] ?: return@forEach
            group.forEach { item ->
                key(item.name) {
                    var expanded by remember { mutableStateOf(false) }
                    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard, onClick = { expanded = !expanded }) {
                        Column(Modifier.padding(ArcoSpacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, null, Modifier.size(16.dp), tint = cat.color)
                                Spacer(Modifier.width(ArcoSpacing.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                                    if (item.summary.isNotBlank()) Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                                }
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = cat.color.copy(alpha = 0.1f)) {
                                    Text(cat.label, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = cat.color)
                                }
                                Spacer(Modifier.width(4.dp))
                                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                            }
                            AnimatedVisibility(visible = expanded) {
                                if (item.docMarkdown.isNotBlank()) MarkdownText(content = item.docMarkdown, modifier = Modifier.padding(top = ArcoSpacing.sm))
                                else Text("暂无文档", Modifier.padding(top = ArcoSpacing.sm), fontSize = 12.sp, color = ThemeColors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInstallDialog) {
        val availableItems = remember(globalPoolItems, loadedItems.size) {
            globalPoolItems.filter { poolItem -> loadedItems.none { it.name == poolItem.name } }
        }
        val selected = remember { mutableStateListOf<FrameworkItem>() }

        AlertDialog(
            onDismissRequest = { selected.clear(); showInstallDialog = false },
            title = { Text(globalPoolLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = {
                if (availableItems.isEmpty()) {
                    Text("全局池中没有更多可安装的项。\n\n方法②和③：Agent 可自行搜索安装，或由用户提供路径后安装。", fontSize = 13.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp)
                } else {
                    Column {
                        Text("选择要安装到当前智能体的项（已安装的不会重复出现）：", fontSize = 12.sp, color = ThemeColors.textSecondary, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
                        availableItems.take(30).forEach { item ->
                            val isChecked = selected.any { it.name == item.name }
                            Row(modifier = Modifier.fillMaxWidth().clickable { if (isChecked) selected.removeAll { it.name == item.name } else selected.add(item) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isChecked, onCheckedChange = { checked -> if (checked) selected.add(item) else selected.removeAll { it.name == item.name } }, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    if (item.summary.isNotBlank()) Text(item.summary, fontSize = 11.sp, color = ThemeColors.textSecondary, maxLines = 1)
                                }
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = item.category.color.copy(alpha = 0.1f)) {
                                    Text(item.category.label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 9.sp, color = item.category.color)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { loadedItems.addAll(selected.filter { item -> loadedItems.none { it.name == item.name } }); selected.clear(); showInstallDialog = false }, enabled = selected.isNotEmpty()) {
                    Text(if (selected.isEmpty()) "安装" else "安装 (${selected.size})")
                }
            },
            dismissButton = { TextButton(onClick = { selected.clear(); showInstallDialog = false }) { Text("取消") } }
        )
    }

    Spacer(Modifier.height(ArcoSpacing.sm))
}
