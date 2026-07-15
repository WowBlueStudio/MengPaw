package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
    onNavigateToAgentSettings: () -> Unit = {},
    onNavigateToMemories: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToExtensionMarket: () -> Unit = {},
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
                            tint = ArcoColors.Blue6,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text(s.settings, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = s.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArcoColors.BgPrimary
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

            // ─── API Token (always visible) ───
            SectionHeader("模型提供商")

            // Provider selector chips
            Column {
                // First row: OpenAI / DeepSeek / Kimi
                Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                    listOf(LlmProviderPreset.OPENAI, LlmProviderPreset.DEEPSEEK, LlmProviderPreset.KIMI).forEach { preset ->
                        FilterChip(
                            selected = state.selectedProvider == preset,
                            onClick = { viewModel.selectProvider(preset) },
                            label = { Text(preset.label.split(" ")[0], fontSize = MaterialTheme.typography.labelMedium.fontSize) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcoColors.Blue1,
                                selectedLabelColor = ArcoColors.Blue6
                            )
                        )
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.sm))
                // Second row: GLM / Qwen / Custom
                Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                    listOf(LlmProviderPreset.GLM, LlmProviderPreset.QWEN, LlmProviderPreset.CUSTOM).forEach { preset ->
                        FilterChip(
                            selected = state.selectedProvider == preset,
                            onClick = { viewModel.selectProvider(preset) },
                            label = { Text(preset.label.split(" ")[0], fontSize = MaterialTheme.typography.labelMedium.fontSize) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcoColors.Blue1,
                                selectedLabelColor = ArcoColors.Blue6
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(ArcoSpacing.lg))

            // API Key (always visible)
            SettingsTextField(
                Icons.Outlined.Key, "API Key", state.apiKey,
                onValueChange = { viewModel.updateApiKey(it) },
                visualTransformation = if (state.showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { viewModel.toggleShowApiKey() }) {
                        Icon(
                            if (state.showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (state.showApiKey) "隐藏" else "显示"
                        )
                    }
                }
            )
            Spacer(Modifier.height(ArcoSpacing.sm))
            SettingsTextField(Icons.Outlined.Link, "API 端点", state.apiEndpoint, onValueChange = { viewModel.updateApiEndpoint(it) })
            Spacer(Modifier.height(ArcoSpacing.sm))
            SettingsTextField(Icons.Outlined.ModelTraining, "模型", state.modelName, onValueChange = { viewModel.updateModelName(it) })

            Spacer(Modifier.height(ArcoSpacing.lg))
            HorizontalDivider(color = ArcoColors.BorderDefault)
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
            HorizontalDivider(color = ArcoColors.BorderDefault)
            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── Agent ───
            SectionHeader(s.agent)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Repeat, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(ArcoSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(s.maxSteps, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(s.maxStepsDesc, style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
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
            HorizontalDivider(color = ArcoColors.BorderDefault)
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
                    Text(s.languageDesc, style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
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
                        color = ArcoColors.Blue6
                    )
                }
            }

            Spacer(Modifier.height(ArcoSpacing.lg))

            // ─── Navigation Links ───
            NavigationLink(Icons.Filled.Favorite, "记忆管理", "查看和编辑 Agent 记忆文档") { onNavigateToMemories() }
            Spacer(Modifier.height(ArcoSpacing.sm))
            NavigationLink(Icons.Outlined.SmartToy, "Agent 设置", "系统提示词、个性、行为配置") { onNavigateToAgentSettings() }

            Spacer(Modifier.height(ArcoSpacing.lg))
            NavigationLink(Icons.Filled.Favorite, "Skills", "查看和管理 Agent 技能") { onNavigateToSkills() }
            Spacer(Modifier.height(ArcoSpacing.sm))
            NavigationLink(Icons.Outlined.Terminal, "执行日志", "查看 Agent 命令执行记录") { onNavigateToLogViewer() }
            Spacer(Modifier.height(ArcoSpacing.sm))
            NavigationLink(Icons.Outlined.Storefront, "扩展市场", "浏览和安装扩展") { onNavigateToExtensionMarket() }

            Spacer(Modifier.height(ArcoSpacing.lg))
            HorizontalDivider(color = ArcoColors.BorderDefault)
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
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
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
        color = ArcoColors.Blue6, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
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
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ArcoColors.Blue6, unfocusedBorderColor = ArcoColors.BorderDefault, focusedLabelColor = ArcoColors.Blue6))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ArcoColors.TextSecondary)
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
            Icon(icon, contentDescription = null, tint = ArcoColors.Blue6, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ArcoColors.Blue6)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ArcoColors.Gray5)
        }
    }
}
