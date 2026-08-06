// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.shell.ui.localization.AppStrings

/** 工作区文件树单行 — 目录展开/文档正文/删除/重置/编辑 (自 AgentSettingsContent 拆分). */
private val RESET_DOCS = setOf(
    "agents.md", "heartbeat.md", "modes.md", "profile.md",
    "soul.md", "trigger.md", "trumanshow.md", "memory/memory.md"
)

/**
 * 工作区文件树单行 — 支持两级: 目录节点(children 非空)点击展开子列表,
 * 文档行(children 空)点击展开 Markdown 正文。
 * @param deletePrefix 相对工作区的路径前缀(子行传 "memory/" / "Notes/"),根层为空。
 */
@Composable
internal fun WorkspaceItemRow(
    item: FrameworkItem,
    onDeleteWorkspaceFile: ((String) -> Unit)?,
    onResetWorkspaceFile: ((String) -> Unit)?,
    onEditWorkspaceFile: ((String) -> Unit)?,
    strings: AppStrings,
    deletePrefix: String = ""
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val isDirectory = item.isFolder || item.children.isNotEmpty()
    // 预置文档(agents/heartbeat/modes/profile/soul/trigger/trumanshow/memory.md)可重置为内置版;
    // 名单外文档(中期/项目记忆、梦境文档等)可删除; 目录节点(memory/Notes)两者皆无
    val resettable = onResetWorkspaceFile != null && !isDirectory && (deletePrefix + item.name) in RESET_DOCS
    val deletable = onDeleteWorkspaceFile != null && !isDirectory && !resettable
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clickable(enabled = isDirectory || item.docMarkdown.isNotBlank()) { expanded = !expanded },
        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                    null, Modifier.size(16.dp),
                    tint = if (isDirectory) ArcoColors.Orange6 else ArcoColors.Blue6
                )
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                    if (item.summary.isNotBlank())
                        Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                }
                if (item.isWowBlue) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Pink6.copy(alpha = 0.1f)) {
                        Text("WowBlue", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 9.sp, color = ArcoColors.Pink6)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (onEditWorkspaceFile != null && !isDirectory) {
                    // 编辑按钮 — 所有 md 文档: 经系统选择器用其他软件打开 (含 MP 浏览器)
                    IconButton(onClick = { onEditWorkspaceFile(deletePrefix + item.name) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Edit, strings.editDoc, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                    }
                }
                if (resettable) {
                    IconButton(onClick = { showResetConfirm = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.RestartAlt, strings.resetDoc, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                    }
                } else if (deletable) {
                    IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Delete, strings.delete, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                    }
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
            AnimatedVisibility(visible = expanded) {
                if (isDirectory) {
                    // 目录展开 → 子文件列表(缩进), 每个子文件自身再展开正文
                    Column(Modifier.padding(start = ArcoSpacing.lg, top = ArcoSpacing.xs)) {
                        item.children.forEach { child ->
                            key(child.name) {
                                WorkspaceItemRow(
                                    item = child,
                                    onDeleteWorkspaceFile = onDeleteWorkspaceFile,
                                    onResetWorkspaceFile = onResetWorkspaceFile,
                                    onEditWorkspaceFile = onEditWorkspaceFile,
                                    strings = strings,
                                    deletePrefix = "${item.name}/"
                                )
                            }
                        }
                    }
                } else if (item.docMarkdown.isNotBlank()) {
                    MarkdownText(content = item.docMarkdown.take(5000),
                        modifier = Modifier.padding(top = ArcoSpacing.sm).heightIn(max = 300.dp))
                }
            }
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(strings.deleteDoc, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = { Text(strings.deleteConfirm, fontSize = 13.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteWorkspaceFile?.invoke(deletePrefix + item.name)
                    showDeleteConfirm = false
                }) { Text(strings.delete, color = ArcoColors.Red6) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel) } }
        )
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(strings.resetDoc, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = { Text(strings.resetConfirm, fontSize = 13.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onResetWorkspaceFile?.invoke(deletePrefix + item.name)
                    showResetConfirm = false
                }) { Text(strings.resetDoc, color = ArcoColors.Blue6) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text(strings.cancel) } }
        )
    }
}
