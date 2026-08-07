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
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * Agent 专属工具面板 — 命令集分组折叠 + 整组删除 (v0.34.1)。
 *
 * 每组 = 一个导入的命令集 (如 飞书 CLI): 组头 = 显示名 + 命令数 + 来源,
 * 点击展开该组命令列表; 命令行内再展开查看用法/描述。
 * 删除按钮仅组头 — 整组删除 = AgentToolsStore.remove (删 {agent}/tools/{name}.json)
 * + AgentToolsSummary.invalidate (系统提示词摘要失效, 对齐 tools.remove 命令层行为)。
 * enName 承载命令集权威名 (文件定位), name 为显示名。
 */
@Composable
internal fun AgentToolsPanel(
    state: SettingsState,
    agentToolItems: List<FrameworkItem>,
    activeAgentName: String
) {
    // ── Agent Tools: 该 Agent 专属工具（非全局共享）— 默认折叠 ──
    var agentToolsExpanded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FrameworkItem?>(null) }
    // 本地可变列表 — 删除后即时移除行; 重开/重扫时由 items 重建 (文件已删, 结果一致)
    val list = remember(agentToolItems) { agentToolItems.toMutableStateList() }

    SectionHeader(state.strings.agentTools, count = "(${agentToolItems.size})",
        expanded = agentToolsExpanded, onToggle = { agentToolsExpanded = !agentToolsExpanded })
    AnimatedVisibility(visible = agentToolsExpanded) {
        Column {
            Text(state.strings.agentToolsDesc,
                fontSize = 11.sp, color = ThemeColors.textSecondary,
                modifier = Modifier.padding(bottom = ArcoSpacing.xs))
            if (list.isEmpty()) {
                Text(state.strings.noToolsConfigured,
                    style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
            } else {
                list.forEach { item ->
                    key(item.name) {
                        var expanded by remember { mutableStateOf(false) }
                        val displayName = item.name // 显示名 (displayName 已由构建端解析, enName=权威名)
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable { expanded = !expanded },
                            shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard
                        ) {
                            Column(Modifier.padding(ArcoSpacing.md)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), tint = ArcoColors.Blue6)
                                    Spacer(Modifier.width(ArcoSpacing.sm))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                                            if (item.enName != null && item.enName != displayName) {
                                                Spacer(Modifier.width(4.dp))
                                                Text(item.enName, fontSize = 10.sp, color = ThemeColors.textSecondary)
                                            }
                                        }
                                        if (item.summary.isNotBlank())
                                            Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                                    }
                                    // 删除整组 — 仅命令集组头 (非全局命令, 用户导入可删)
                                    IconButton(onClick = { deleteTarget = item }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Outlined.Delete, state.strings.delete, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                                    }
                                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                                }
                                AnimatedVisibility(visible = expanded) {
                                    // 展开: 子命令列表 (缩进), 命令行再展开看用法/描述
                                    if (item.children.isNotEmpty()) {
                                        Column(Modifier.padding(start = ArcoSpacing.lg, top = ArcoSpacing.xs)) {
                                            item.children.forEach { child ->
                                                key(child.name) {
                                                    var childExpanded by remember { mutableStateOf(false) }
                                                    Surface(
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                        shape = RoundedCornerShape(ArcoRadius.sm),
                                                        color = ThemeColors.bgCardHigh,
                                                        onClick = { childExpanded = !childExpanded }
                                                    ) {
                                                        Column(Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Column(Modifier.weight(1f)) {
                                                                    Text(child.name, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                                                        color = ThemeColors.textPrimary)
                                                                    if (child.summary.isNotBlank())
                                                                        Text(child.summary, fontSize = 11.sp, color = ThemeColors.textSecondary, maxLines = 2)
                                                                }
                                                                Icon(if (childExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                                    null, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                                                            }
                                                            AnimatedVisibility(visible = childExpanded) {
                                                                if (child.docMarkdown.isNotBlank()) {
                                                                    Text(child.docMarkdown, Modifier.padding(top = ArcoSpacing.xs),
                                                                        fontSize = 12.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (item.docMarkdown.isNotBlank()) {
                                        Text(item.docMarkdown, Modifier.padding(top = ArcoSpacing.sm),
                                            fontSize = 12.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除整组确认 — 命令集文件 + 提示词摘要同步失效
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(state.strings.deleteDoc, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = {
                Text(
                    state.strings.agentToolDeleteConfirm.replace("%s", target.name),
                    fontSize = 13.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    val setName = target.enName ?: target.name
                    com.mengpaw.plugin.agenttools.AgentToolsStore.remove(activeAgentName, setName)
                    com.mengpaw.plugin.agenttools.AgentToolsSummary.invalidate(activeAgentName)
                    list.removeAll { it == target }
                    deleteTarget = null
                }) { Text(state.strings.delete, color = ArcoColors.Red6) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(state.strings.cancel) } }
        )
    }
}
