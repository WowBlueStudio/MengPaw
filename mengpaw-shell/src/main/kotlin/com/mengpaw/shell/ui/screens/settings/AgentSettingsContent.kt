// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import com.mengpaw.design.components.SectionHeader

@Composable
fun AgentSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    activeEndpoint: String,
    activeModel: String,
    onSelectProvider: ((SavedProvider) -> Unit)?,
    agentPluginItems: List<FrameworkItem> = emptyList(),
    globalToolItems: List<FrameworkItem> = emptyList(),    // self.tools 索引 (只读)
    agentToolItems: List<FrameworkItem> = emptyList(),     // Agent 分区工具
    globalSkillItems: List<FrameworkItem> = emptyList(),   // 全局 Skills 池 (skill.ls)
    agentSkillItems: List<FrameworkItem> = emptyList(),    // Agent 本地 Skills
    workspaceItems: List<FrameworkItem> = emptyList(),
    onRefreshWorkspace: (() -> Unit)? = null
) {
    SectionHeader(state.strings.agentProviderModel)
    if (state.savedProviders.isEmpty()) {
        Text(state.strings.agentNoProvider,
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
    } else {
        state.savedProviders.forEach { saved ->
            val active = saved.endpoint == activeEndpoint && saved.model == activeModel
            var expanded by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                shape = RoundedCornerShape(ArcoRadius.lg),
                color = if (active) ArcoColors.Blue1.copy(alpha = 0.4f) else ThemeColors.bgCard,
                tonalElevation = if (active) 2.dp else 0.dp
            ) {
                Column {
                    Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.brandContainer) {
                            Icon(Icons.Outlined.Key, null, tint = ThemeColors.brand, modifier = Modifier.size(32.dp).padding(6.dp))
                        }
                        Spacer(Modifier.width(ArcoSpacing.md))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(saved.preset.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                if (active) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.12f)) {
                                        Text(state.strings.sidebarCurrent, Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                                    }
                                }
                            }
                            Text(saved.model, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                        }
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, Modifier.size(20.dp), tint = ThemeColors.textSecondary
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(Modifier.padding(start = ArcoSpacing.lg, end = ArcoSpacing.md, bottom = ArcoSpacing.sm)) {
                            Text(state.strings.agentSelectModel, style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                            saved.preset.models.forEach { model ->
                                val selected = saved.model == model.name
                                Row(Modifier.fillMaxWidth().clickable {
                                    viewModel.updateModelName(model.name)
                                    onSelectProvider?.invoke(saved.copy(model = model.name))
                                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selected, onClick = {
                                        viewModel.updateModelName(model.name)
                                        onSelectProvider?.invoke(saved.copy(model = model.name))
                                    }, modifier = Modifier.size(18.dp),
                                        colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                                    Spacer(Modifier.width(8.dp))
                                    Text(model.name, Modifier.weight(1f), fontSize = 13.sp)
                                    if (model.type == "Coding") Text("💻", fontSize = 12.sp)
                                    else if (model.type == "多模态") Text("🖼", fontSize = 12.sp)
                                    else if (model.type.contains("思维链")) Text("🧠", fontSize = 12.sp)
                                }
                            }
                            val extraModels = state.remoteModels.filter { rm -> saved.preset.models.none { it.name == rm } }
                            if (extraModels.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text(state.strings.agentApiReturnedModels, fontSize = 10.sp, color = ArcoColors.Green6,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
                                extraModels.take(20).forEach { model ->
                                    val selected = saved.model == model
                                    Row(Modifier.fillMaxWidth().clickable {
                                        viewModel.updateModelName(model)
                                        onSelectProvider?.invoke(saved.copy(model = model))
                                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selected, onClick = {
                                            viewModel.updateModelName(model)
                                            onSelectProvider?.invoke(saved.copy(model = model))
                                        }, modifier = Modifier.size(18.dp),
                                            colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                                        Spacer(Modifier.width(8.dp))
                                        Text(model, Modifier.weight(1f), fontSize = 13.sp, color = ArcoColors.Green6)
                                    }
                                }
                                if (extraModels.size > 20) {
                                    Text(String.format(state.strings.agentRemainingCount, extraModels.size - 20),
                                        fontSize = 10.sp, color = ThemeColors.textSecondary,
                                        modifier = Modifier.padding(start = ArcoSpacing.sm))
                                }
                            }
                            TextButton(onClick = {
                                viewModel.selectProvider(saved.preset)
                                viewModel.updateApiKey(saved.apiKey)
                                viewModel.refreshModels()
                            }) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(state.strings.agentRefreshModels, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader(state.strings.agentParams)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Repeat, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.maxSteps, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.maxStepsDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        var stepsText by remember(state.maxSteps) { mutableStateOf(state.maxSteps.toString()) }
        OutlinedTextField(value = stepsText, onValueChange = { stepsText = it; it.toIntOrNull()?.let { n -> viewModel.updateMaxSteps(n) } },
            modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, shape = RoundedCornerShape(ArcoRadius.md))
    }

    Spacer(Modifier.height(ArcoSpacing.sm))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.NetworkCheck, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.llmConcurrency, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.llmConcurrencyDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        var concurrencyText by remember(state.llmMaxConcurrency) { mutableStateOf(state.llmMaxConcurrency.toString()) }
        OutlinedTextField(value = concurrencyText, onValueChange = { concurrencyText = it; it.toIntOrNull()?.let { n -> viewModel.updateLlmMaxConcurrency(n) } },
            modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, shape = RoundedCornerShape(ArcoRadius.md))
    }

    Spacer(Modifier.height(ArcoSpacing.sm))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Timer, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.agentShellTimeout, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(state.strings.agentShellTimeoutDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        var tText by remember(state.commandTimeoutSec) { mutableStateOf(state.commandTimeoutSec.toString()) }
        OutlinedTextField(value = tText, onValueChange = { tText = it; it.toIntOrNull()?.let { n -> viewModel.updateCommandTimeout(n) } },
            modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, shape = RoundedCornerShape(ArcoRadius.md))
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    SectionHeader(state.strings.agentLoopMode)
    Text(state.strings.agentLoopModeDesc,
        style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary,
        modifier = Modifier.padding(bottom = ArcoSpacing.xs))
    LoopMode.entries.forEach { mode ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            shape = RoundedCornerShape(ArcoRadius.md),
            color = ThemeColors.bgCard
        ) {
            Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(mode.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                    Text(mode.desc, fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
            }
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Chat, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.agentLanguage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.agentLanguageDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        OutlinedButton(onClick = { viewModel.cycleAgentLanguage() },
            shape = RoundedCornerShape(ArcoRadius.md),
            contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm)) {
            Text(
                text = when (state.agentLanguageMode) {
                    AgentLanguageMode.FOLLOW_UI -> state.strings.agentLanguageFollowUi
                    AgentLanguageMode.CHINESE -> state.strings.agentLanguageChinese
                    AgentLanguageMode.ENGLISH -> state.strings.agentLanguageEnglish
                }, fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

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
            onDismiss = { showAddCronDialog = false },
            onConfirm = { id, expr, action ->
                com.mengpaw.kernel.trigger.TriggerEngine.addCron(id, expr, action)
                triggerVersion++
                showAddCronDialog = false
            }
        )
    }

    if (showAddLifetimeDialog) {
        LifetimeTriggerDialog(
            onDismiss = { showAddLifetimeDialog = false },
            onConfirm = { id, timeRange, action ->
                com.mengpaw.kernel.trigger.TriggerEngine.addSchedule(id, timeRange, action)
                triggerVersion++
                showAddLifetimeDialog = false
            }
        )
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── Tools: 全局工具索引 (只读, 无安装流程) ──
    SectionHeader(state.strings.agentTools + " (${globalToolItems.size})")
    Text(state.strings.agentInstallHelp, fontSize = 11.sp, color = ThemeColors.textSecondary,
        lineHeight = 16.sp, modifier = Modifier.padding(bottom = ArcoSpacing.xs))
    if (globalToolItems.isNotEmpty()) {
        FrameworkItemSection("", Icons.Outlined.Terminal, globalToolItems)
    } else {
        Text(state.strings.agentNoTriggers, style = MaterialTheme.typography.bodySmall,
            color = ThemeColors.textSecondary)
    }

    if (agentToolItems.isNotEmpty()) {
        Spacer(Modifier.height(ArcoSpacing.md))
        SectionHeader("分区工具 (${agentToolItems.size})")
        FrameworkItemSection("", Icons.Outlined.Terminal, agentToolItems)
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── Skills: Agent 本地 + 全局池 ──
    AgentItemsSection(
        title = state.strings.agentSkills, icon = Icons.Outlined.AutoAwesome,
        agentItems = agentSkillItems, globalPoolItems = globalSkillItems,
        globalPoolLabel = state.strings.agentFromSkillPool,
        installHelp = state.strings.agentInstallHelp
    )

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── 全局 Skills 池索引 (只读) ──
    SectionHeader(state.strings.agentSkillPool + " (${globalSkillItems.size})")
    Text("全局 Skills 池中的可用项，使用 skill.pull <name> 拉取到当前 Agent。",
        fontSize = 11.sp, color = ThemeColors.textSecondary,
        modifier = Modifier.padding(bottom = ArcoSpacing.xs))
    if (globalSkillItems.isNotEmpty()) {
        // Global skills pool list with pull buttons
        Column {
            globalSkillItems.forEach { item ->
                key(item.name) {
                    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
                        Row(Modifier.padding(start = ArcoSpacing.md, end = ArcoSpacing.sm, top = ArcoSpacing.sm, bottom = ArcoSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = ArcoColors.Blue6)
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                                if (item.summary.isNotBlank())
                                    Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            TextButton(onClick = {
                                /* skill.pull via Agent CLI — user asks Agent in chat */
                            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                Text("拉取", fontSize = 12.sp, color = ThemeColors.brand)
                            }
                        }
                    }
                }
            }
        }
    } else {
        Text(state.strings.agentNoTriggers, style = MaterialTheme.typography.bodySmall,
            color = ThemeColors.textSecondary)
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(state.strings.agentWorkspaceFiles)
        Spacer(Modifier.weight(1f))
        if (onRefreshWorkspace != null) {
            IconButton(onClick = onRefreshWorkspace, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Refresh, state.strings.agentRefreshFileList, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
        }
    }
    FrameworkItemSection("", Icons.Outlined.Description, workspaceItems)
}
