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

@Composable
fun AgentSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    activeEndpoint: String,
    activeModel: String,
    onSelectProvider: ((SavedProvider) -> Unit)?,
    agentToolItems: List<FrameworkItem> = emptyList(),     // Agent 专属工具
    agentSkillItems: List<FrameworkItem> = emptyList(),    // Agent 本地 Skills
    workspaceItems: List<FrameworkItem> = emptyList(),
    onRefreshWorkspace: (() -> Unit)? = null,
    onDeleteWorkspaceFile: ((String) -> Unit)? = null      // 按文件名删除工作区文档（如 boost.md）
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
                                Text(if (state.useChinese) saved.preset.label else saved.preset.enLabel, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 英文 UI 用枚举英文字段（此前英文界面也显示中文）
                        Text(if (state.useChinese) mode.label else mode.enLabel,
                            fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                        // WowBlue 徽标: 火种模式 + 步坦协同（原创特性）
                        if (mode == LoopMode.SWARM || mode == LoopMode.FLEET) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Pink6.copy(alpha = 0.1f)) {
                                Text("WowBlue", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = ArcoColors.Pink6)
                            }
                        }
                    }
                    Text(if (state.useChinese) mode.desc else mode.enDesc,
                        fontSize = 12.sp, color = ThemeColors.textSecondary)
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

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

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

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── Agent Skills: 该 Agent 本地技能，动态列表 — 默认折叠 ──
    var agentSkillsExpanded by remember { mutableStateOf(false) }
    SectionHeader(state.strings.agentSkills, count = "(${agentSkillItems.size})",
        expanded = agentSkillsExpanded, onToggle = { agentSkillsExpanded = !agentSkillsExpanded })
    AnimatedVisibility(visible = agentSkillsExpanded) {
        Column {
    Text(state.strings.agentSkillsDesc,
        fontSize = 11.sp, color = ThemeColors.textSecondary,
        modifier = Modifier.padding(bottom = ArcoSpacing.xs))
    if (agentSkillItems.isNotEmpty()) {
        agentSkillItems.forEach { item ->
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
    if (workspaceItems.isEmpty()) {
        Text(state.strings.noWorkspaceDocs, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
    } else {
        workspaceItems.forEach { item ->
            key(item.name) {
                WorkspaceItemRow(
                    item = item,
                    onDeleteWorkspaceFile = onDeleteWorkspaceFile,
                    strings = state.strings
                )
            }
        }
    }
}

/**
 * 工作区文件树单行 — 支持两级: 目录节点(children 非空)点击展开子列表,
 * 文档行(children 空)点击展开 Markdown 正文。
 * @param deletePrefix 相对工作区的路径前缀(子行传 "memory/"),根层为空。
 */
@Composable
private fun WorkspaceItemRow(
    item: FrameworkItem,
    onDeleteWorkspaceFile: ((String) -> Unit)?,
    strings: AppStrings,
    deletePrefix: String = ""
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isDirectory = item.children.isNotEmpty()
    // memory 目录节点只读容器; 文档行(含 memory 子文件)可删除
    val deletable = onDeleteWorkspaceFile != null && !isDirectory
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
                if (deletable) {
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
}
