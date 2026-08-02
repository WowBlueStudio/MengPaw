// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.llm.CacheStrategy
import com.mengpaw.design.components.SectionHeader

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun FrameworkSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    onNavigateToPluginMarket: () -> Unit,
    pluginItems: List<FrameworkItem> = emptyList(),
    toolItems: List<FrameworkItem> = emptyList(),
    skillItems: List<FrameworkItem> = emptyList()
) {
    SectionHeader(state.strings.frameworkApiProvider)

    if (state.savedProviders.isNotEmpty()) {
        state.savedProviders.forEach { saved ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.editProvider(saved) },
                shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.bgCard
            ) {
                Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.brandContainer) {
                        Icon(Icons.Outlined.Key, null, tint = ThemeColors.brand, modifier = Modifier.size(32.dp).padding(6.dp))
                    }
                    Spacer(Modifier.width(ArcoSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(saved.preset.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Text(saved.endpoint.take(40),
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                    }
                    if (saved.balance.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                            Text("$${saved.balance}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green7)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { viewModel.removeProvider(saved.preset) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, state.strings.delete, tint = ThemeColors.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
        }
    }

    // ── 角色模型路由（Fleet/火种）──
    SectionHeader("角色模型路由（Fleet/火种）")
    Text("各角色可用不同模型：规划/验收用强模型、执行用便宜模型；未配置的角色回退主模型。",
        style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
    Spacer(Modifier.height(ArcoSpacing.sm))
    if (state.savedProviders.isEmpty()) {
        Text("请先在上方保存至少一个模型连接，再为角色分配模型。",
            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
    } else {
        SettingsViewModel.SWARM_ROLES.forEach { role ->
            val current = state.swarmRoles[role]
            var menuExpanded by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(SettingsViewModel.roleLabel(role), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(0.45f))
                Box {
                    Surface(
                        onClick = { menuExpanded = true },
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = if (current != null) ThemeColors.brand.copy(alpha = 0.12f) else ThemeColors.bgCardHigh
                    ) {
                        Text(
                            if (current != null) "${current.preset.label} · ${current.model}" else "默认（主模型）",
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            color = if (current != null) ThemeColors.brand else ThemeColors.textPrimary,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("默认（主模型）") }, onClick = {
                            viewModel.setSwarmRole(role, null); menuExpanded = false
                        })
                        state.savedProviders.forEach { sp ->
                            DropdownMenuItem(text = { Text("${sp.preset.label} · ${sp.model}") }, onClick = {
                                viewModel.setSwarmRole(role, sp); menuExpanded = false
                            })
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(ArcoSpacing.md))

    if (state.apiSectionExpanded) {
        SectionHeader(if (state.savedProviders.isNotEmpty()) state.strings.frameworkEditConnection else state.strings.frameworkNewConnection)

        // Provider preset chips
        Text(state.strings.frameworkProviderLabel, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(4.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LlmProviderPreset.entries.filter { it != LlmProviderPreset.CUSTOM && it != LlmProviderPreset.SELF_HOSTED }
                .forEach { preset ->
                    Surface(
                        onClick = { viewModel.selectProvider(preset) },
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = if (state.selectedProvider == preset) ThemeColors.brand.copy(alpha = 0.12f) else ThemeColors.bgCardHigh
                    ) {
                        Text(preset.label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            fontSize = 12.sp, fontWeight = if (state.selectedProvider == preset) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (state.selectedProvider == preset) ThemeColors.brand else ThemeColors.textPrimary)
                    }
                }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(LlmProviderPreset.SELF_HOSTED, LlmProviderPreset.CUSTOM).forEach { preset ->
                Surface(
                    onClick = { viewModel.selectProvider(preset) },
                    shape = RoundedCornerShape(ArcoRadius.sm),
                    color = if (state.selectedProvider == preset) ThemeColors.brand.copy(alpha = 0.12f) else ThemeColors.bgCardHigh
                ) {
                    Text(preset.label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        fontSize = 12.sp, fontWeight = if (state.selectedProvider == preset) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (state.selectedProvider == preset) ThemeColors.brand else ThemeColors.textPrimary)
                }
            }
        }
        Spacer(Modifier.height(ArcoSpacing.sm))

        SettingsTextField(Icons.Outlined.Key, state.strings.apiKey, state.apiKey,
            onValueChange = { viewModel.updateApiKey(it) },
            visualTransformation = if (state.showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { viewModel.toggleShowApiKey() }) {
                    Icon(if (state.showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (state.showApiKey) state.strings.apiKeyHide else state.strings.apiKeyShow)
                }
            })
        Spacer(Modifier.height(ArcoSpacing.sm))
        SettingsTextField(Icons.Outlined.Link, state.strings.apiEndpoint, state.apiEndpoint,
            onValueChange = { viewModel.updateApiEndpoint(it) })
        Spacer(Modifier.height(ArcoSpacing.sm))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            OutlinedButton(onClick = { viewModel.testConnection() }, modifier = Modifier.weight(1f),
                enabled = !state.isTesting && state.apiKey.isNotBlank(), shape = RoundedCornerShape(ArcoRadius.md)) {
                if (state.isTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ThemeColors.brand)
                else Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(state.strings.frameworkTestConnection, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { viewModel.saveApiKey() }, modifier = Modifier.weight(1f),
                enabled = state.apiKey.isNotBlank(), shape = RoundedCornerShape(ArcoRadius.md)) {
                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(state.strings.frameworkSaveConnection, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (state.isTesting) {
            Spacer(Modifier.height(4.dp))
            Text(state.strings.frameworkTestingConnection, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }

        Spacer(Modifier.height(ArcoSpacing.sm))
        // 收起按钮（替代原来独立按钮的功能）
        OutlinedButton(onClick = { viewModel.toggleApiSection() },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.ExpandLess, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(state.strings.frameworkCollapseList, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(ArcoSpacing.lg))
    } else {
        OutlinedButton(onClick = { viewModel.expandForNewProvider() },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(state.strings.frameworkAddProvider)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader(state.strings.frameworkMemoryManagement)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.strings.frameworkMemoryBackend, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (state.memoryBackend == "builtin") state.strings.frameworkMemoryBackendDesc else state.memoryBackend,
                style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.bgCardHigh) {
            Text(state.strings.frameworkRequiresPlugin, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ThemeColors.textSecondary)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader(state.strings.frameworkContextStrategy)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.strings.frameworkContextStrategy, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (state.contextStrategy == "default") state.strings.frameworkContextStrategyDesc else state.contextStrategy,
                style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.bgCardHigh) {
            Text(state.strings.frameworkRequiresPlugin, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ThemeColors.textSecondary)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SecurityRulesSection(strings = state.strings)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    NavigationLink(Icons.Outlined.Extension, state.strings.frameworkPluginManagement, state.strings.frameworkPluginManagementDesc) { onNavigateToPluginMarket() }
    Spacer(Modifier.height(ArcoSpacing.lg))

    // 三个列表区块默认折叠 — 减少初始设置列表，点击 header 展开
    var pluginsExpanded by remember { mutableStateOf(false) }
    SectionHeader(state.strings.frameworkGlobalPlugins, count = "(${pluginItems.size})",
        expanded = pluginsExpanded, onToggle = { pluginsExpanded = !pluginsExpanded })
    AnimatedVisibility(visible = pluginsExpanded) {
        Column {
            FrameworkItemSection("", Icons.Outlined.Extension, pluginItems)
        }
    }
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    var toolsExpanded by remember { mutableStateOf(false) }
    SectionHeader(state.strings.frameworkGlobalTools, count = "(${toolItems.size})",
        expanded = toolsExpanded, onToggle = { toolsExpanded = !toolsExpanded })
    AnimatedVisibility(visible = toolsExpanded) {
        Column {
            FrameworkItemSection("", Icons.Outlined.Terminal, toolItems)
        }
    }
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    var skillsExpanded by remember { mutableStateOf(false) }
    SectionHeader(state.strings.frameworkGlobalSkills, count = "(${skillItems.size})",
        expanded = skillsExpanded, onToggle = { skillsExpanded = !skillsExpanded })
    AnimatedVisibility(visible = skillsExpanded) {
        Column {
            FrameworkItemSection("", Icons.Outlined.AutoAwesome, skillItems)
        }
    }
}
