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

/** Agent 专属工具面板 (自 AgentSettingsContent 拆分). */
@Composable
internal fun AgentToolsPanel(
    state: SettingsState,
    agentToolItems: List<FrameworkItem>,
) {
    // ── Agent Tools: 该 Agent 专属工具（非全局共享）— 默认折叠 ──
    var agentToolsExpanded by remember { mutableStateOf(false) }
    SectionHeader(state.strings.agentTools, count = "(${agentToolItems.size})",
        expanded = agentToolsExpanded, onToggle = { agentToolsExpanded = !agentToolsExpanded })
    AnimatedVisibility(visible = agentToolsExpanded) {
        Column {
    Text(state.strings.agentToolsDesc,
        fontSize = 11.sp, color = ThemeColors.textSecondary,
        modifier = Modifier.padding(bottom = ArcoSpacing.xs))
    if (agentToolItems.isNotEmpty()) {
        agentToolItems.forEach { item ->
            key(item.name) {
                var expanded by remember { mutableStateOf(false) }
                Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(enabled = item.docMarkdown.isNotBlank()) { expanded = !expanded },
                    shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
                    Column(Modifier.padding(ArcoSpacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), tint = ArcoColors.Blue6)
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
        Text(state.strings.noToolsConfigured,
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
    }
        }
    }
}
