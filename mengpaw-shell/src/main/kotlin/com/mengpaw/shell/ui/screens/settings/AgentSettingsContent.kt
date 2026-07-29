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

@Composable
fun AgentSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    activeEndpoint: String,
    activeModel: String,
    onSelectProvider: ((SavedProvider) -> Unit)?,
    agentPluginItems: List<FrameworkItem> = emptyList(),
    agentToolItems: List<FrameworkItem> = emptyList(),
    agentSkillItems: List<FrameworkItem> = emptyList(),
    globalToolItems: List<FrameworkItem> = emptyList(),
    globalSkillItems: List<FrameworkItem> = emptyList(),
    workspaceItems: List<FrameworkItem> = emptyList(),
    onRefreshWorkspace: (() -> Unit)? = null
) {
    SectionHeader("供应商 & 模型")
    if (state.savedProviders.isEmpty()) {
        Text("尚未添加 API 供应商，请先前往「框架设置」配置",
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
                                        Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
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
                            Text("选择模型", style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                            // Preset models
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
                            // API-fetched models (not in preset)
                            val extraModels = state.remoteModels.filter { rm -> saved.preset.models.none { it.name == rm } }
                            if (extraModels.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text("API 返回模型", fontSize = 10.sp, color = ArcoColors.Green6,
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
                                    Text("... 还有 ${extraModels.size - 20} 个",
                                        fontSize = 10.sp, color = ThemeColors.textSecondary,
                                        modifier = Modifier.padding(start = ArcoSpacing.sm))
                                }
                            }
                            // Refresh button
                            TextButton(onClick = {
                                viewModel.selectProvider(saved.preset)
                                viewModel.updateApiKey(saved.apiKey)
                                viewModel.refreshModels()
                            }) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("刷新模型列表", style = MaterialTheme.typography.labelSmall)
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

    SectionHeader("Agent 参数")

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
            Text("Shell 命令超时", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("单个命令最长执行时间（秒）", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        var tText by remember(state.commandTimeoutSec) { mutableStateOf(state.commandTimeoutSec.toString()) }
        OutlinedTextField(value = tText, onValueChange = { tText = it; it.toIntOrNull()?.let { n -> viewModel.updateCommandTimeout(n) } },
            modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, shape = RoundedCornerShape(ArcoRadius.md))
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    SectionHeader("Loop 模式")
    Text("Agent 会根据任务复杂度自动选择合适的执行模式，无需手动切换。",
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

    SectionHeader("定时任务 & 触发器")
    var triggerVersion by remember { mutableStateOf(0) }
    var showAddCronDialog by remember { mutableStateOf(false) }
    var showAddLifetimeDialog by remember { mutableStateOf(false) }
    val triggers = remember(triggerVersion) { com.mengpaw.kernel.trigger.TriggerEngine.list() }

    if (triggers.isEmpty()) {
        Text("暂无触发器。添加定时任务让 Agent 在指定时间自动执行。",
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
                                    if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON) "定时" else "真人",
                                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    fontSize = 9.sp, color = if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON)
                                        ArcoColors.Blue6 else ArcoColors.Orange6)
                            }
                        }
                        Text("${trigger.config} → ${trigger.action.take(40)}",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                        if (trigger.lastFired > 0) {
                            Text(
                                "上次触发: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(trigger.lastFired))}",
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
                        Icon(Icons.Outlined.Close, "删除", Modifier.size(16.dp), tint = ThemeColors.textSecondary)
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
            Text("添加定时", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = { showAddLifetimeDialog = true },
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.Person, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加真人感", style = MaterialTheme.typography.labelSmall)
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
                com.mengpaw.kernel.trigger.TriggerEngine.addLifetime(id, timeRange, action)
                triggerVersion++
                showAddLifetimeDialog = false
            }
        )
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    FrameworkItemSection("Agent 插件", Icons.Outlined.Extension, agentPluginItems)
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentItemsSection(
        title = "智能体工具(Agent Tools)", icon = Icons.Outlined.Terminal,
        agentItems = agentToolItems, globalPoolItems = globalToolItems,
        globalPoolLabel = "从全局工具池安装",
        installHelp = "三种安装方式：①从全局工具池安装 ②Agent 自行搜索下载安装 ③用户手动下载并提供路径，Agent 自行安装"
    )

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentItemsSection(
        title = "智能体技能(Agent Skills)", icon = Icons.Outlined.AutoAwesome,
        agentItems = agentSkillItems, globalPoolItems = globalSkillItems,
        globalPoolLabel = "从全局技能池安装",
        installHelp = "三种安装方式：①从全局技能池安装 ②Agent 自行搜索下载安装 ③用户手动下载并提供路径，Agent 自行安装"
    )

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader("工作区文件")
        Spacer(Modifier.weight(1f))
        if (onRefreshWorkspace != null) {
            IconButton(onClick = onRefreshWorkspace, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Refresh, "刷新文件列表", Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
        }
    }
    FrameworkItemSection("", Icons.Outlined.Description, workspaceItems)
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = ThemeColors.brand, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
}
