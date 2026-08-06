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

/** Agent 参数面板 — 最大步数/并发/Shell 超时/Loop 模式/Agent 语言 (自 AgentSettingsContent 拆分). */
@Composable
internal fun AgentParamsPanel(
    state: SettingsState,
    viewModel: SettingsViewModel,
) {
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
}
