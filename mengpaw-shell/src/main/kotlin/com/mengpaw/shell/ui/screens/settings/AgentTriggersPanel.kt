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

/** 定时任务与触发器面板 — 触发器列表 + CRON/伪人模式添加对话框 (自 AgentSettingsContent 拆分). */
@Composable
internal fun AgentTriggersPanel(
    state: SettingsState,
    viewModel: SettingsViewModel,
) {
    SectionHeader(state.strings.agentTriggers)
    var triggerVersion by remember { mutableStateOf(0) }
    var showAddCronDialog by remember { mutableStateOf(false) }
    var showAddLifetimeDialog by remember { mutableStateOf(false) }
    val triggers = remember(triggerVersion) { com.mengpaw.kernel.trigger.TriggerEngine.list() }

    if (triggers.isEmpty()) {
        Text(state.strings.agentNoTriggers,
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
    } else {
        triggers.forEach { trigger ->
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
                Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON) Icons.Outlined.Schedule
                        else Icons.Outlined.Person, null,
                        tint = if (trigger.enabled) ThemeColors.brand else ThemeColors.textSecondary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(trigger.id, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp),
                                color = if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON)
                                    ArcoColors.Blue6.copy(alpha = 0.1f) else ArcoColors.Orange6.copy(alpha = 0.1f)) {
                                Text(
                                    if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON) state.strings.agentTriggerScheduled else state.strings.agentTriggerHuman,
                                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    fontSize = 9.sp, color = if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON)
                                        ArcoColors.Blue6 else ArcoColors.Orange6)
                            }
                        }
                        Text("${trigger.config} → ${trigger.action.take(40)}",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                        if (trigger.lastFired > 0) {
                            Text(
                                String.format(state.strings.agentLastFired, java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(trigger.lastFired))),
                                fontSize = 10.sp, color = ArcoColors.Gray6)
                        }
                    }
                    Switch(checked = trigger.enabled, onCheckedChange = { newChecked ->
                        if (!newChecked) com.mengpaw.kernel.trigger.TriggerEngine.disable(trigger.id)
                        else com.mengpaw.kernel.trigger.TriggerEngine.enable(trigger.id)
                        triggerVersion++
                    }, modifier = Modifier.size(32.dp))
                    IconButton(onClick = {
                        com.mengpaw.kernel.trigger.TriggerEngine.remove(trigger.id)
                        triggerVersion++
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, state.strings.delete, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                    }
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
        OutlinedButton(onClick = { showAddCronDialog = true },
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(state.strings.agentAddScheduled, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = { showAddLifetimeDialog = true },
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.Person, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(state.strings.agentAddHumanTouch, style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showAddCronDialog) {
        CronTriggerDialog(
            strings = state.strings,            onDismiss = { showAddCronDialog = false },
            onConfirm = { id, expr, action ->
                com.mengpaw.kernel.trigger.TriggerEngine.addCron(id, expr, action)
                triggerVersion++
                showAddCronDialog = false
            }
        )
    }

    if (showAddLifetimeDialog) {
        LifetimeTriggerDialog(
            strings = state.strings,            onDismiss = { showAddLifetimeDialog = false },
            onConfirm = { id, timeRange, action ->
                com.mengpaw.kernel.trigger.TriggerEngine.addSchedule(id, timeRange, action)
                triggerVersion++
                showAddLifetimeDialog = false
            }
        )
    }
}
