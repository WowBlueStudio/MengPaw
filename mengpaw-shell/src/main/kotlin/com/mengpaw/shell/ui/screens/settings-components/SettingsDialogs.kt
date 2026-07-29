// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

@Composable
fun CronTriggerDialog(
    onDismiss: () -> Unit,
    onConfirm: (id: String, cronExpr: String, action: String) -> Unit
) {
    var selectedPreset by remember { mutableStateOf(0) }
    var customCron by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }

    data class CronPreset(val label: String, val cron: String, val hint: String)
    val presets = listOf(
        CronPreset("每天早上 9:00", "0 9 * * *", "生成昨日摘要并推送"),
        CronPreset("每天中午 12:00", "0 12 * * *", "检查今日待办事项"),
        CronPreset("每天晚上 20:00", "0 20 * * *", "总结今日工作进展"),
        CronPreset("每小时整点", "0 * * * *", "检查系统状态"),
        CronPreset("自定义 (输入Cron表达式)", "", "")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加定时任务", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                Text("选择时间", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)
                presets.forEachIndexed { i, preset ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selectedPreset = i; if (preset.cron.isNotBlank()) customCron = preset.cron },
                        shape = RoundedCornerShape(ArcoRadius.sm), color = if (selectedPreset == i) ThemeColors.brand.copy(alpha = 0.08f) else ThemeColors.bgCardHigh
                    ) {
                        Row(Modifier.padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedPreset == i, onClick = { selectedPreset = i; if (preset.cron.isNotBlank()) customCron = preset.cron }, modifier = Modifier.size(20.dp), colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Column {
                                Text(preset.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (preset.cron.isNotBlank()) Text("CRON: ${preset.cron}", fontSize = 10.sp, color = ThemeColors.textSecondary, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                if (selectedPreset == presets.size - 1 || selectedPreset >= 0) {
                    OutlinedTextField(
                        value = customCron, onValueChange = { customCron = it; selectedPreset = presets.size - 1 },
                        label = { Text("Cron 表达式") }, placeholder = { Text("分 时 日 月 周，如: 0 9 * * *") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md),
                        supportingText = { Text("模糊窗口 ±${com.mengpaw.kernel.trigger.TriggerEngine.cronFuzzyWindowMinutes} 分钟，无需精确到秒", fontSize = 10.sp, color = ThemeColors.textSecondary) }
                    )
                }
                OutlinedTextField(
                    value = action, onValueChange = { action = it },
                    label = { Text("执行动作") }, placeholder = { Text(presets.getOrNull(selectedPreset)?.hint ?: "描述 Agent 需要执行的任务...") },
                    minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { val expr = customCron.ifBlank { presets[selectedPreset].cron }; val act = action.ifBlank { presets[selectedPreset].hint }; if (expr.isNotBlank() && act.isNotBlank()) onConfirm("cron-${(1000..9999).random()}", expr, act) },
                enabled = customCron.isNotBlank() && action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand), shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun LifetimeTriggerDialog(
    onDismiss: () -> Unit,
    onConfirm: (id: String, timeRange: String, action: String) -> Unit
) {
    var startHour by remember { mutableStateOf("10") }
    var startMin by remember { mutableStateOf("00") }
    var endHour by remember { mutableStateOf("20") }
    var endMin by remember { mutableStateOf("00") }
    var action by remember { mutableStateOf("") }
    var selectedTopic by remember { mutableStateOf(-1) }
    val topics = remember { com.mengpaw.kernel.trigger.TriggerEngine.LIFETIME_TOPICS }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加真人感触发器", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                Text("在这个时间段内随机触发一次", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                    OutlinedTextField(value = startHour, onValueChange = { if (it.length <= 2) startHour = it }, label = { Text("开始时") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(":", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = startMin, onValueChange = { if (it.length <= 2) startMin = it }, label = { Text("分") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("—", fontSize = 18.sp, color = ThemeColors.textSecondary)
                    OutlinedTextField(value = endHour, onValueChange = { if (it.length <= 2) endHour = it }, label = { Text("结束时") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(":", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = endMin, onValueChange = { if (it.length <= 2) endMin = it }, label = { Text("分") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("话题预设", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)
                topics.forEachIndexed { i, topic ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selectedTopic = i; action = topic },
                        shape = RoundedCornerShape(ArcoRadius.sm), color = if (selectedTopic == i) ThemeColors.brand.copy(alpha = 0.08f) else ThemeColors.bgCardHigh
                    ) {
                        Row(Modifier.padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedTopic == i, onClick = { selectedTopic = i; action = topic }, modifier = Modifier.size(20.dp), colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Text(topic, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                OutlinedTextField(value = action, onValueChange = { action = it; selectedTopic = -1 }, label = { Text("自定义动作") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md))
            }
        },
        confirmButton = {
            Button(
                onClick = { val timeRange = "${startHour}:${startMin}-${endHour}:${endMin}"; if (action.isNotBlank()) onConfirm("chat-${(1000..9999).random()}", timeRange, action) },
                enabled = action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand), shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
