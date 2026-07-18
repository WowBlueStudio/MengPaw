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
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

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
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                        listOf(LlmProviderPreset.OPENAI, LlmProviderPreset.DEEPSEEK, LlmProviderPreset.KIMI).forEach { preset ->
                            FilterChip(selected = state.selectedProvider == preset,
                                onClick = { viewModel.selectProvider(preset) },
                                label = { Text(preset.label.split(" ")[0], fontSize = MaterialTheme.typography.labelMedium.fontSize) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ArcoColors.Blue1, selectedLabelColor = ArcoColors.Blue6))
                        }
                    }
                    Spacer(Modifier.height(ArcoSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                        listOf(LlmProviderPreset.GLM, LlmProviderPreset.QWEN, LlmProviderPreset.CUSTOM).forEach { preset ->
                            FilterChip(selected = state.selectedProvider == preset,
                                onClick = { viewModel.selectProvider(preset) },
                                label = { Text(preset.label.split(" ")[0], fontSize = MaterialTheme.typography.labelMedium.fontSize) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ArcoColors.Blue1, selectedLabelColor = ArcoColors.Blue6))
                        }
                    }
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

                // ── Model list ──
                val models = state.selectedProvider.models
                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(ArcoSpacing.sm))
                    Text("可用模型", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Surface(shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard, tonalElevation = 1.dp) {
                        Column {
                            val displayModels = if (models.size <= 5) models else models.take(5)
                            displayModels.forEach { model ->
                                Row(Modifier.fillMaxWidth().clickable { viewModel.updateModelName(model.name) }
                                    .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.modelName == model.name,
                                        onClick = { viewModel.updateModelName(model.name) },
                                        modifier = Modifier.size(20.dp),
                                        colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                                    Spacer(Modifier.width(ArcoSpacing.sm))
                                    Text(model.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (state.modelName == model.name) FontWeight.Medium else FontWeight.Normal)
                                    Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                                        color = if (model.type == "多模态") ArcoColors.Orange1 else ArcoColors.Blue1) {
                                        Text(model.type, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (model.type == "多模态") ArcoColors.Orange7 else ArcoColors.Blue7)
                                    }
                                }
                            }
                            if (models.size > 5) {
                                OutlinedButton(onClick = { /* TODO: scrollable model picker */ },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.sm, vertical = 4.dp),
                                    shape = RoundedCornerShape(ArcoRadius.md)) {
                                    Text("查看全部 ${models.size} 个模型...", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

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
            SettingsSwitch(
                icon = Icons.Outlined.SmartToy,
                title = s.useSimulated,
                subtitle = s.useSimulatedDesc,
                checked = state.useSimulatedProvider,
                onCheckedChange = { viewModel.toggleSimulatedProvider() }
            )

            if (!state.useSimulatedProvider) {
                Spacer(Modifier.height(ArcoSpacing.sm))
                SettingsTextField(Icons.Outlined.ModelTraining, s.model, state.modelName, onValueChange = { viewModel.updateModelName(it) })
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
