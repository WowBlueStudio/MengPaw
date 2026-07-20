// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens
import androidx.compose.material.icons.outlined.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.core.llm.CacheStrategy
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * Settings screen for MengPaw configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPluginMarket: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val s = state.strings

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = com.mengpaw.design.theme.ThemeColors.brand,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text(s.settings, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = s.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ArcoSpacing.lg)
        ) {
            Spacer(Modifier.height(ArcoSpacing.md))

            // ─── Saved provider list (collapsed rows) ───
            if (state.savedProviders.isNotEmpty()) {
                SectionHeader("已配置的模型")
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
                                Text("${saved.model} · ${saved.endpoint.take(35)}",
                                    style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            if (saved.balance.isNotBlank()) {
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                                    Text("$${saved.balance}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green7)
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { viewModel.removeProvider(saved.preset) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, "删除", tint = ThemeColors.textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(ArcoSpacing.sm))
                }
                // Add another provider button
                OutlinedButton(onClick = { viewModel.expandForNewProvider() },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加模型提供商")
                }
                Spacer(Modifier.height(ArcoSpacing.lg))
            }

            // ─── Expanded editor ───
            if (state.apiSectionExpanded) {
                SectionHeader(if (state.savedProviders.isNotEmpty()) "编辑模型配置" else "模型提供商")
                // Collapsible provider list — each provider expands to show models
                LlmProviderPreset.entries.forEach { preset ->
                    if (preset != LlmProviderPreset.CUSTOM && preset != LlmProviderPreset.SELF_HOSTED) {
                        ProviderCard(
                            preset = preset,
                            isSelected = state.selectedProvider == preset,
                            selectedModel = state.modelName,
                            remoteModels = state.remoteModels,
                            onSelect = { viewModel.selectProvider(preset) },
                            onSelectModel = { viewModel.updateModelName(it) }
                        )
                    }
                }
                ProviderCard(LlmProviderPreset.SELF_HOSTED, state.selectedProvider == LlmProviderPreset.SELF_HOSTED, state.modelName, state.remoteModels, { viewModel.selectProvider(LlmProviderPreset.SELF_HOSTED) }, { viewModel.updateModelName(it) })
                ProviderCard(LlmProviderPreset.CUSTOM, state.selectedProvider == LlmProviderPreset.CUSTOM, state.modelName, state.remoteModels, { viewModel.selectProvider(LlmProviderPreset.CUSTOM) }, { viewModel.updateModelName(it) })
                Spacer(Modifier.height(ArcoSpacing.sm))

                // ── Cache optimization indicator ──
                val cacheStrategy = CacheStrategy.forProvider(state.apiEndpoint)
                if (cacheStrategy != CacheStrategy.NONE) {
                    Surface(
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = Color(0xFF52C41A).copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = ArcoSpacing.md, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = Color(0xFF52C41A))
                            Spacer(Modifier.width(6.dp))
                            Text(CacheStrategy.labelFor(cacheStrategy),
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF52C41A))
                            Spacer(Modifier.weight(1f))
                            Text("已优化", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold, color = Color(0xFF52C41A))
                        }
                    }
                    Spacer(Modifier.height(ArcoSpacing.sm))
                }

                Spacer(Modifier.height(ArcoSpacing.lg))

                SettingsTextField(Icons.Outlined.Key, "API Key", state.apiKey,
                    onValueChange = { viewModel.updateApiKey(it) },
                    visualTransformation = if (state.showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { viewModel.toggleShowApiKey() }) { Icon(if (state.showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (state.showApiKey) "隐藏" else "显示") } })
                Spacer(Modifier.height(ArcoSpacing.sm))
                SettingsTextField(Icons.Outlined.Link, "API 地址", state.apiEndpoint, onValueChange = { viewModel.updateApiEndpoint(it) })
                Spacer(Modifier.height(ArcoSpacing.sm))
                SettingsTextField(Icons.Outlined.ModelTraining, "模型", state.modelName, onValueChange = { viewModel.updateModelName(it) })

                Spacer(Modifier.height(ArcoSpacing.sm))

                // Action row
                Spacer(Modifier.height(ArcoSpacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                    OutlinedButton(onClick = { viewModel.testConnection() },
                        modifier = Modifier.weight(1f), enabled = !state.isTesting && state.apiKey.isNotBlank(),
                        shape = RoundedCornerShape(ArcoRadius.md)) {
                        if (state.isTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ThemeColors.brand)
                        else Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("测试", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { viewModel.saveApiKey() },
                        modifier = Modifier.weight(1f), enabled = state.apiKey.isNotBlank(),
                        shape = RoundedCornerShape(ArcoRadius.md)) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("保存", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { viewModel.toggleApiSection() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(ArcoRadius.md)) {
                        Text("收起", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (state.isTesting) {
                    Spacer(Modifier.height(4.dp))
                    Text("正在测试连接...", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                }
            }

            Spacer(Modifier.height(ArcoSpacing.lg))
            HorizontalDivider(color = com.mengpaw.design.theme.ThemeColors.border)
            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── LLM Provider ───
            SectionHeader(s.llmProvider)
            if (state.apiKey.isNotBlank()) {
                Spacer(Modifier.height(ArcoSpacing.sm))
                SettingsTextField(Icons.Outlined.ModelTraining, s.model, state.modelName, onValueChange = { viewModel.updateModelName(it) })
            } else {
                Text("填入 API Key 后自动启用真实模型", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.padding(horizontal = ArcoSpacing.lg))
            }

            Spacer(Modifier.height(ArcoSpacing.lg))
            HorizontalDivider(color = com.mengpaw.design.theme.ThemeColors.border)
            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── Agent ───
            SectionHeader(s.agent)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Repeat, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(ArcoSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(s.maxSteps, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(s.maxStepsDesc, style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                }
                var stepsText by remember(state.maxSteps) { mutableStateOf(state.maxSteps.toString()) }
                OutlinedTextField(
                    value = stepsText,
                    onValueChange = { stepsText = it; it.toIntOrNull()?.let { n -> viewModel.updateMaxSteps(n) } },
                    modifier = Modifier.width(80.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
            }

            // Shell command timeout
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
                    modifier = Modifier.width(72.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = RoundedCornerShape(ArcoRadius.md))
            }

            // Timezone
            Spacer(Modifier.height(ArcoSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Language, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(ArcoSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text("时区", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Text(state.timezone, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                }
                TextButton(onClick = { viewModel.updateTimezone(if (state.timezone == "Asia/Shanghai") java.util.TimeZone.getDefault().id else "Asia/Shanghai") }) {
                    Text(if (state.timezone == "Asia/Shanghai") "自动" else "上海")
                }
            }

            Spacer(Modifier.height(ArcoSpacing.lg))
            HorizontalDivider(color = com.mengpaw.design.theme.ThemeColors.border)
            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── Appearance ───
            SectionHeader(s.appearance)
            SettingsSwitch(
                icon = Icons.Outlined.DarkMode,
                title = s.darkTheme,
                subtitle = s.darkThemeDesc,
                checked = state.darkTheme,
                onCheckedChange = { viewModel.toggleDarkTheme() }
            )

            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── Language (一键切换) ───
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Translate, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(ArcoSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(s.language, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(s.languageDesc, style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                }
                // Language toggle button
                OutlinedButton(
                    onClick = { viewModel.toggleLanguage() },
                    shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm)
                ) {
                    Text(
                        if (state.useChinese) s.languageEn else s.languageZh,
                        fontWeight = FontWeight.SemiBold,
                        color = com.mengpaw.design.theme.ThemeColors.brand
                    )
                }
            }

            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── Agent Language (LLM output language) ───
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Chat, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(ArcoSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(s.agentLanguage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(s.agentLanguageDesc, style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                }
                OutlinedButton(
                    onClick = { viewModel.cycleAgentLanguage() },
                    shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm)
                ) {
                    Text(
                        text = when (state.agentLanguageMode) {
                            AgentLanguageMode.FOLLOW_UI -> s.agentLanguageFollowUi
                            AgentLanguageMode.CHINESE -> s.agentLanguageChinese
                            AgentLanguageMode.ENGLISH -> s.agentLanguageEnglish
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = com.mengpaw.design.theme.ThemeColors.brand
                    )
                }
            }

            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── 触发器 Triggers ───
            SectionHeader("触发器 Triggers")
            val triggers = remember { com.mengpaw.core.trigger.TriggerEngine.list() }
            if (triggers.isEmpty()) {
                Text("暂无触发器。Agent 可使用 self.trigger 命令创建。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary, modifier = Modifier.padding(horizontal = ArcoSpacing.lg))
                Spacer(Modifier.height(ArcoSpacing.sm))
            } else {
                triggers.forEach { trigger ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg, vertical = 2.dp),
                        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
                        Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (trigger.type == com.mengpaw.core.trigger.TriggerEngine.TriggerType.CRON) Icons.Outlined.Schedule else Icons.Outlined.Person, null,
                                tint = if (trigger.enabled) ThemeColors.brand else ThemeColors.textSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(trigger.id, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                Text("${trigger.config} → ${trigger.action.take(30)}", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            Switch(checked = trigger.enabled, onCheckedChange = {
                                if (trigger.enabled) com.mengpaw.core.trigger.TriggerEngine.disable(trigger.id)
                                else com.mengpaw.core.trigger.TriggerEngine.enable(trigger.id)
                            }, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
            // Quick-add lifetime trigger
            OutlinedButton(onClick = {
                com.mengpaw.core.trigger.TriggerEngine.addLifetime("chat-${(1000..9999).random()}", "10:00-20:00", "随机和用户聊聊")
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg), shape = RoundedCornerShape(ArcoRadius.md)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加真人感触发器", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── 电池优化 ───
            Row(Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("后台省电模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("降低后台轮询频率和动画帧率，延长续航", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary) }
                Switch(checked = true, onCheckedChange = { /* toggle low-power mode */ })
            }
            OutlinedButton(onClick = {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = android.net.Uri.parse("package:com.mengpaw.shell")
                    // context.startActivity not directly available here
                } catch (_: Exception) {}
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg), shape = RoundedCornerShape(ArcoRadius.md)) {
                Text("忽略电池优化（推荐开启）", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(ArcoSpacing.md))

            // ─── PAD 悬浮窗 ───
            val context = androidx.compose.ui.platform.LocalContext.current
            var dotEnabled by remember { mutableStateOf<Boolean>(com.mengpaw.plugin.pad.PadPlugin.isVisible()) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("PAD 悬浮窗", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("呼吸灯小圆点显示 Agent 工作状态", style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                }
                Switch(checked = dotEnabled, onCheckedChange = { checked ->
                    dotEnabled = checked
                    if (checked) com.mengpaw.plugin.pad.PadPlugin.show()
                    else com.mengpaw.plugin.pad.PadPlugin.hide()
                })
            }
            Spacer(Modifier.height(ArcoSpacing.sm))

            // ─── Navigation Link ───
            NavigationLink(Icons.Outlined.Extension, "插件管理 (Plugin Manager)", "浏览、安装、管理 Agent 插件") { onNavigateToPluginMarket() }

            Spacer(Modifier.height(ArcoSpacing.lg))
            HorizontalDivider(color = com.mengpaw.design.theme.ThemeColors.border)
            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── Advanced ───
            SectionHeader("高级设置")
            // Context strategy
            Row(Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("上下文策略", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Text(if (state.contextStrategy == "default") "内置 · Reasonix 四级折叠" else state.contextStrategy, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                }
                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Gray3) {
                    Text("需安装插件", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ArcoColors.Gray6)
                }
            }
            // Memory backend
            Spacer(Modifier.height(ArcoSpacing.sm))
            Row(Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记忆管理后端", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Text(if (state.memoryBackend == "memory-plugin") "内置 · Markdown 文件" else state.memoryBackend, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                }
                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Gray3) {
                    Text("需安装插件", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ArcoColors.Gray6)
                }
            }

            Spacer(Modifier.height(ArcoSpacing.lg))
            HorizontalDivider(color = com.mengpaw.design.theme.ThemeColors.border)
            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── About ───
            SectionHeader(s.about)
            InfoRow(s.version, "0.1.0-alpha")
            InfoRow(s.core, "mengpaw-core")
            InfoRow(s.design, "Arco.design")

            Spacer(Modifier.height(ArcoSpacing.lg))

            OutlinedButton(
                onClick = { viewModel.resetToDefaults() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(ArcoRadius.lg),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ArcoColors.Red6)
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text(s.resetDefaults)
            }
            Spacer(Modifier.height(ArcoSpacing.xxxl))
        }
    }
}

// ─── Helpers ───

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = com.mengpaw.design.theme.ThemeColors.brand, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
}

@Composable
private fun SettingsSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm)) {
        Icon(icon, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = ArcoColors.Blue6))
    }
}

@Composable
private fun SettingsTextField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, value: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(ArcoRadius.md),
            visualTransformation = visualTransformation, trailingIcon = trailingIcon,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ArcoColors.Blue6, unfocusedBorderColor = com.mengpaw.design.theme.ThemeColors.border, focusedLabelColor = ArcoColors.Blue6))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NavigationLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(ArcoRadius.lg),
        color = ArcoColors.Gray1,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = com.mengpaw.design.theme.ThemeColors.brand, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = com.mengpaw.design.theme.ThemeColors.brand)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = ArcoColors.Gray5)
        }
    }
}

@Composable
private fun ProviderCard(
    preset: LlmProviderPreset,
    isSelected: Boolean,
    selectedModel: String,
    remoteModels: List<String> = emptyList(),
    onSelect: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(isSelected) }
    if (isSelected && !expanded) expanded = true

    val strategy = CacheStrategy.forProvider(preset.endpoint)
    val optimized = strategy != CacheStrategy.NONE

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = if (isSelected) ArcoColors.Blue1.copy(alpha = 0.3f) else ThemeColors.bgCard,
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(); expanded = !expanded }
                    .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(18.dp), tint = ThemeColors.textSecondary
                )
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text(preset.label, Modifier.weight(1f),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp, color = if (isSelected) ArcoColors.Blue6 else ThemeColors.textPrimary)
                if (optimized) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = Color(0xFF52C41A).copy(alpha = 0.1f)) {
                        Text("已优化", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 10.sp, color = Color(0xFF52C41A))
                    }
                }
                if (isSelected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = ArcoColors.Blue6)
                }
            }
            // Expanded model list
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = ArcoSpacing.lg, end = ArcoSpacing.md, bottom = ArcoSpacing.sm)) {
                    Text(preset.endpoint.take(60), fontSize = 11.sp, color = ThemeColors.textSecondary,
                        modifier = Modifier.padding(bottom = 6.dp))
                    // Preset models
                    preset.models.forEach { model ->
                        Row(Modifier.fillMaxWidth().clickable { onSelectModel(model.name) }
                            .padding(vertical = 4.dp, horizontal = ArcoSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedModel == model.name, onClick = { onSelectModel(model.name) },
                                modifier = Modifier.size(18.dp), colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                            Spacer(Modifier.width(8.dp))
                            Text(model.name, Modifier.weight(1f), fontSize = 13.sp)
                            if (model.type == "多模态") Text("🖼", fontSize = 12.sp)
                        }
                    }
                    // Remote models (fetched from API)
                    if (remoteModels.isNotEmpty()) {
                        Text("API 返回", fontSize = 10.sp, color = Color(0xFF52C41A), modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                        remoteModels.take(10).filter { it !in preset.models.map { m -> m.name } }.forEach { model ->
                            Row(Modifier.fillMaxWidth().clickable { onSelectModel(model) }
                                .padding(vertical = 4.dp, horizontal = ArcoSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedModel == model, onClick = { onSelectModel(model) },
                                    modifier = Modifier.size(18.dp), colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                                Spacer(Modifier.width(8.dp))
                                Text(model, Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF52C41A))
                            }
                        }
                    }
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelectModel(model.name) }
                                .padding(vertical = 4.dp, horizontal = ArcoSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedModel == model.name,
                                onClick = { onSelectModel(model.name) },
                                modifier = Modifier.size(18.dp),
                                colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(model.name, Modifier.weight(1f), fontSize = 13.sp)
                            Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                                color = if (model.type == "多模态") ArcoColors.Orange1 else ArcoColors.Blue1) {
                                Text(model.type, Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 10.sp,
                                    color = if (model.type == "多模态") ArcoColors.Orange7 else ArcoColors.Blue7)
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
