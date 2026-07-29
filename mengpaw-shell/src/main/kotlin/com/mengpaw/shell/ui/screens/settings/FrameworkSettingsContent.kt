// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
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

@Composable
fun FrameworkSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    onNavigateToPluginMarket: () -> Unit,
    pluginItems: List<FrameworkItem> = emptyList(),
    toolItems: List<FrameworkItem> = emptyList(),
    skillItems: List<FrameworkItem> = emptyList()
) {
    SectionHeader("API供应商")

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
                        Icon(Icons.Filled.Close, "删除", tint = ThemeColors.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
        }
    }

    if (state.apiSectionExpanded) {
        SectionHeader(if (state.savedProviders.isNotEmpty()) "编辑连接" else "新增连接")

        // Provider preset chips
        Text("供应商", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

        SettingsTextField(Icons.Outlined.Key, "API Key", state.apiKey,
            onValueChange = { viewModel.updateApiKey(it) },
            visualTransformation = if (state.showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { viewModel.toggleShowApiKey() }) {
                    Icon(if (state.showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (state.showApiKey) "隐藏" else "显示")
                }
            })
        Spacer(Modifier.height(ArcoSpacing.sm))
        SettingsTextField(Icons.Outlined.Link, "API 地址", state.apiEndpoint,
            onValueChange = { viewModel.updateApiEndpoint(it) })
        Spacer(Modifier.height(ArcoSpacing.sm))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            OutlinedButton(onClick = { viewModel.testConnection() }, modifier = Modifier.weight(1f),
                enabled = !state.isTesting && state.apiKey.isNotBlank(), shape = RoundedCornerShape(ArcoRadius.md)) {
                if (state.isTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ThemeColors.brand)
                else Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("测试", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { viewModel.saveApiKey() }, modifier = Modifier.weight(1f),
                enabled = state.apiKey.isNotBlank(), shape = RoundedCornerShape(ArcoRadius.md)) {
                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (state.isTesting) {
            Spacer(Modifier.height(4.dp))
            Text("正在测试连接...", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }

        Spacer(Modifier.height(ArcoSpacing.sm))
        // 收起按钮（替代原来独立按钮的功能）
        OutlinedButton(onClick = { viewModel.toggleApiSection() },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.ExpandLess, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("收起API供应商列表", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(ArcoSpacing.lg))
    } else {
        OutlinedButton(onClick = { viewModel.expandForNewProvider() },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("新增API供应商")
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    NavigationLink(Icons.Outlined.Extension, "插件管理", "浏览、安装、管理 Agent 插件") { onNavigateToPluginMarket() }
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader("记忆管理")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("记忆管理后端", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (state.memoryBackend == "memory-plugin") "内置 · Markdown 文件" else state.memoryBackend,
                style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Gray3) {
            Text("需安装插件", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ArcoColors.Gray6)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader("上下文策略")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("上下文策略", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (state.contextStrategy == "default") "内置 · Reasonix 四级折叠" else state.contextStrategy,
                style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Gray3) {
            Text("需安装插件", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ArcoColors.Gray6)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SecurityRulesSection()

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    FrameworkItemSection("全局插件", Icons.Outlined.Extension, pluginItems)
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    FrameworkItemSection("全局工具(Tools)", Icons.Outlined.Terminal, toolItems)
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    FrameworkItemSection("全局工具(Skills)", Icons.Outlined.AutoAwesome, skillItems)
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = ThemeColors.brand, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
}
