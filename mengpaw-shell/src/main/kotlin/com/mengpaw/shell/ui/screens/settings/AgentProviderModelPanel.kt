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

/** Provider & Model 面板 — 供应商卡片选择 + API 返回模型 + 自动翻译开关 (自 AgentSettingsContent 拆分). */
@Composable
internal fun AgentProviderModelPanel(
    state: SettingsState,
    viewModel: SettingsViewModel,
    activeEndpoint: String,
    activeModel: String,
    onSelectProvider: ((SavedProvider) -> Unit)?,
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
                                    if (model.type == "Coding") Icon(Icons.Outlined.Code, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                                    else if (model.type == "多模态") Icon(Icons.Outlined.Image, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                                    else if (model.type.contains("全模态")) Icon(Icons.Outlined.Mic, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                                    else if (model.type.contains("思维链")) Icon(Icons.Outlined.Psychology, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
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
    // 自动翻译 (美系模型) — opt-in, 默认关闭 (v0.28.6)
    SettingsSwitch(
        icon = Icons.Outlined.Translate,
        title = state.strings.agentAutoTranslate,
        subtitle = state.strings.agentAutoTranslateDesc,
        checked = state.autoTranslate,
        onCheckedChange = { viewModel.toggleAutoTranslate() }
    )
}
