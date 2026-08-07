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

/**
 * Agent 本地技能面板 (自 AgentSettingsContent 拆分) — v0.34.1 起支持单条删除。
 * 删除 = 删 {agent}/skills/{name}.md + 列表即时移除 (对齐 skill.rm 命令层行为,
 * listSkills 实时扫目录, 无缓存需失效; 对齐 GlobalSkillPoolPanel 确认对话框模式)。
 */
@Composable
internal fun AgentSkillsPanel(
    state: SettingsState,
    agentSkillItems: List<FrameworkItem>,
    activeAgentName: String
) {
    // ── Agent Skills: 该 Agent 本地技能，动态列表 — 默认折叠 ──
    var agentSkillsExpanded by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // 本地可变列表 — 删除后即时移除行; 重开/重扫时由 items 重建 (文件已删, 结果一致)
    val list = remember(agentSkillItems) { agentSkillItems.toMutableStateList() }
    SectionHeader(state.strings.agentSkills, count = "(${agentSkillItems.size})",
        expanded = agentSkillsExpanded, onToggle = { agentSkillsExpanded = !agentSkillsExpanded })
    AnimatedVisibility(visible = agentSkillsExpanded) {
        Column {
            Text(state.strings.agentSkillsDesc,
                fontSize = 11.sp, color = ThemeColors.textSecondary,
                modifier = Modifier.padding(bottom = ArcoSpacing.xs))
            if (list.isNotEmpty()) {
                list.forEach { item ->
                    key(item.name) {
                        var expanded by remember { mutableStateOf(false) }
                        Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(enabled = item.docMarkdown.isNotBlank()) { expanded = !expanded },
                            shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
                            Column(Modifier.padding(ArcoSpacing.md)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = ArcoColors.Blue6)
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
                                    // 删除单条 — 本地技能文件 (无预置概念, 全部用户可删)
                                    IconButton(onClick = { deleteTarget = item.name }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Outlined.Delete, state.strings.delete, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                                    }
                                    if (item.docMarkdown.isNotBlank()) {
                                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                                    }
                                }
                                AnimatedVisibility(visible = expanded) {
                                    if (item.docMarkdown.isNotBlank()) {
                                        MarkdownText(content = item.docMarkdown, modifier = Modifier.padding(top = ArcoSpacing.sm))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text(state.strings.agentNoSkills, style = MaterialTheme.typography.bodySmall,
                    color = ThemeColors.textSecondary)
            }
        }
    }

    // 删除确认 — 单条技能文件; name 来自 listFiles 的 fileNameWithoutExtension (已消毒, 无路径分隔符)
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(state.strings.deleteDoc, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = { Text(state.strings.deleteConfirm, fontSize = 13.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    // 技能 = md 剧本 + 同名资源文件夹 (脚本/流程) — 两者一起删, 防残留
                    val skillsDir = java.io.File(com.mengpaw.kernel.DataPaths.agentSkillsDir(activeAgentName))
                    try { java.io.File(skillsDir, "$target.md").delete() } catch (_: Exception) {}
                    try { java.io.File(skillsDir, target).deleteRecursively() } catch (_: Exception) {}
                    list.removeAll { it.name == target }
                    deleteTarget = null
                }) { Text(state.strings.delete, color = ArcoColors.Red6) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(state.strings.cancel) } }
        )
    }
}
