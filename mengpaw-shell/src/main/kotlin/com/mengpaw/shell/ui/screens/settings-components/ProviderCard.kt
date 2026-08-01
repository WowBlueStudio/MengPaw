// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.llm.CacheStrategy

@Composable
fun ProviderCard(
    preset: LlmProviderPreset,
    isSelected: Boolean,
    selectedModel: String,
    remoteModels: List<String> = emptyList(),
    onSelect: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(isSelected) }
    LaunchedEffect(isSelected) { if (isSelected) expanded = true else expanded = false }

    val strategy = CacheStrategy.forProvider(preset.endpoint)
    val optimized = strategy != CacheStrategy.NONE

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = if (isSelected) ArcoColors.Blue1.copy(alpha = 0.3f) else ThemeColors.bgCard,
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { onSelect(); expanded = !expanded }.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text(preset.label, Modifier.weight(1f), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp, color = if (isSelected) ArcoColors.Blue6 else ThemeColors.textPrimary)
                if (optimized) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green6.copy(alpha = 0.1f)) {
                        Text("已优化", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = ArcoColors.Green6)
                    }
                }
                if (isSelected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = ArcoColors.Blue6)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = ArcoSpacing.lg, end = ArcoSpacing.md, bottom = ArcoSpacing.sm)) {
                    Text(preset.endpoint.take(60), fontSize = 11.sp, color = ThemeColors.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    preset.models.forEach { model ->
                        Row(Modifier.fillMaxWidth().clickable { onSelectModel(model.name) }.padding(vertical = 4.dp, horizontal = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedModel == model.name, onClick = { onSelectModel(model.name) }, modifier = Modifier.size(18.dp), colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                            Spacer(Modifier.width(8.dp))
                            Text(model.name, Modifier.weight(1f), fontSize = 13.sp)
                            if (model.type == "多模态") Text("🖼", fontSize = 12.sp)
                        }
                    }
                    if (remoteModels.isNotEmpty()) {
                        Text("API 返回", fontSize = 10.sp, color = ArcoColors.Green6, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                        remoteModels.take(10).filter { it !in preset.models.map { m -> m.name } }.forEach { model ->
                            Row(Modifier.fillMaxWidth().clickable { onSelectModel(model) }.padding(vertical = 4.dp, horizontal = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedModel == model, onClick = { onSelectModel(model) }, modifier = Modifier.size(18.dp), colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                                Spacer(Modifier.width(8.dp))
                                Text(model, Modifier.weight(1f), fontSize = 13.sp, color = ArcoColors.Green6)
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
