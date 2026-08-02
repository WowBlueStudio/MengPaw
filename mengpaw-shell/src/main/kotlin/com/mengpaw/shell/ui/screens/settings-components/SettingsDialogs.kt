// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.localization.AppStrings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/** CRON 触发器 — 简洁输入，让用户找 Agent 配置时间参数。 */
@Composable
fun CronTriggerDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (id: String, cronExpr: String, action: String) -> Unit
) {
    var cronExpr by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.cronTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                OutlinedTextField(
                    value = cronExpr, onValueChange = { cronExpr = it },
                    label = { Text(strings.cronExpression) },
                    placeholder = { Text(strings.cronPlaceholder) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md),
                    supportingText = { Text(strings.cronHint, fontSize = 11.sp, color = ThemeColors.textSecondary) }
                )
                OutlinedTextField(
                    value = action, onValueChange = { action = it },
                    label = { Text(strings.actionLabel) },
                    placeholder = { Text(strings.actionPlaceholder) },
                    minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
                Text(strings.cronTip,
                    fontSize = 11.sp, color = ThemeColors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (cronExpr.isNotBlank() && action.isNotBlank()) onConfirm("cron-${(1000..9999).random()}", cronExpr, action) },
                enabled = cronExpr.isNotBlank() && action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand), shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text(strings.add, color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

/** SCHEDULE 日程触发器 — 简洁输入，让用户找 Agent 配置时间参数。 */
@Composable
fun LifetimeTriggerDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (id: String, config: String, action: String) -> Unit
) {
    var config by remember { mutableStateOf("08:00-22:00,count=3,interval=60") }
    var action by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.scheduleTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                OutlinedTextField(
                    value = config, onValueChange = { config = it },
                    label = { Text(strings.scheduleParams) },
                    placeholder = { Text(strings.scheduleParamsPlaceholder) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md),
                    supportingText = { Text(strings.scheduleFormatHint, fontSize = 11.sp, color = ThemeColors.textSecondary) }
                )
                OutlinedTextField(
                    value = action, onValueChange = { action = it },
                    label = { Text(strings.actionLabel) },
                    placeholder = { Text(strings.actionPlaceholder) },
                    minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
                Text(strings.scheduleTip,
                    fontSize = 11.sp, color = ThemeColors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (config.isNotBlank() && action.isNotBlank()) onConfirm("chat-${(1000..9999).random()}", config, action) },
                enabled = config.isNotBlank() && action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand), shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text(strings.add, color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}
