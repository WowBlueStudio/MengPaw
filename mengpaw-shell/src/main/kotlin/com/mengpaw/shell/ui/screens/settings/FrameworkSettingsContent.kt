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
import androidx.compose.ui.graphics.Color
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
import com.mengpaw.shell.ui.localization.AppStrings

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
    // ── 框架名片 (v0.34.3 + v0.35.1 两行重构): 行1 名称+编辑 / 行2 指纹码+设备标识 ──
    SectionHeader(state.strings.frameworkCardTitle)
    var identityName by remember {
        mutableStateOf(com.mengpaw.plugin.framework.FrameworkIdentity.displayName)
    }
    var editingName by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCardHigh
    ) {
        Column(Modifier.padding(ArcoSpacing.lg)) {
            // 行1: {框架名称} — 右侧仅一个"编辑"按钮 (编辑态 = 输入框 + 保存)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editingName) {
                    OutlinedTextField(
                        value = identityName,
                        onValueChange = { identityName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(state.strings.frameworkIdentityDefaultPlaceholder) }
                    )
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Button(
                        onClick = {
                            com.mengpaw.plugin.framework.FrameworkIdentity.setDisplayName(identityName)
                            // 名称变更 → 重新注册 mDNS (display 属性随下次注册生效)
                            com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.unregister()
                            com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.register()
                            editingName = false
                        },
                        shape = RoundedCornerShape(ArcoRadius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand)
                    ) { Text(state.strings.cardSave, color = Color.White) }
                } else {
                    Text(
                        com.mengpaw.plugin.framework.FrameworkIdentity.displayName
                            .ifBlank { com.mengpaw.plugin.framework.FrameworkIdentity.shortCode },
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    TextButton(onClick = {
                        identityName = com.mengpaw.plugin.framework.FrameworkIdentity.displayName
                        editingName = true
                    }) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(state.strings.cardEdit, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
            // 行2: 本机指纹码 MengPaw Android {android id}
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        // v0.35.1: 指纹/设备标识无效时省略对应段 (不再显示 "本机指纹码 no-mac · ")
                        val fp = com.mengpaw.plugin.framework.FrameworkIdentity.fingerprint
                        val fpValid = fp.isNotBlank() &&
                            !fp.endsWith("no-mac") && !fp.endsWith("no-device-id")
                        val rawId = com.mengpaw.plugin.framework.FrameworkIdentity.deviceRawId()
                        if (fpValid) {
                            append(state.strings.frameworkIdentityLabel)
                            append(" ")
                            append(com.mengpaw.plugin.framework.FrameworkIdentity.shortCode)
                        }
                        if (rawId.isNotBlank()) {
                            if (fpValid) append(" · ")
                            append("MengPaw Android ")
                            append(rawId)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ThemeColors.textSecondary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

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
                        Text(if (state.useChinese) saved.preset.label else saved.preset.enLabel, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
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


    if (state.apiSectionExpanded) {
        SectionHeader(if (state.savedProviders.isNotEmpty()) state.strings.frameworkEditConnection else state.strings.frameworkNewConnection)

        // Provider preset chips
        Text(state.strings.frameworkProviderLabel, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(4.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // v0.41.0: 预置供应商按英文名首字母排序 (自建/自定义除外, 单独一行)
            LlmProviderPreset.presetChipOrder().forEach { preset ->
                    Surface(
                        onClick = { viewModel.selectProvider(preset) },
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = if (state.selectedProvider == preset) ThemeColors.brand.copy(alpha = 0.12f) else ThemeColors.bgCardHigh
                    ) {
                        Text(if (state.useChinese) preset.label else preset.enLabel, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
                    Text(if (state.useChinese) preset.label else preset.enLabel, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
    }

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
            FrameworkItemSection("", Icons.Outlined.Extension, pluginItems, state.strings)
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
            // 专用面板: 全量命令按来源分组(核心/插件) — 通用 FrameworkItemSection 的 内置/官方 标签已退役
            GlobalToolPoolPanel(items = toolItems, strings = state.strings)
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
            // 专用面板: 来源标签(核心/插件) + @指定 + 删除(仅非预置) — 通用 FrameworkItemSection 无按钮
            GlobalSkillPoolPanel(items = skillItems, strings = state.strings)
        }
    }
}
