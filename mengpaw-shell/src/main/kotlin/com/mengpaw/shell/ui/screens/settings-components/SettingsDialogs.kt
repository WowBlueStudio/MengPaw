// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

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
    onDismiss: () -> Unit,
    onConfirm: (id: String, cronExpr: String, action: String) -> Unit
) {
    var cronExpr by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CRON 定时任务", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                OutlinedTextField(
                    value = cronExpr, onValueChange = { cronExpr = it },
                    label = { Text("Cron 表达式") },
                    placeholder = { Text("分 时 日 月 周  例: 0 9 * * *") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md),
                    supportingText = { Text("时间参数让 Agent 帮你算，例如：帮我每天早上9点生成昨日摘要", fontSize = 11.sp, color = ThemeColors.textSecondary) }
                )
                OutlinedTextField(
                    value = action, onValueChange = { action = it },
                    label = { Text("执行动作") },
                    placeholder = { Text("描述 Agent 需要执行的任务...") },
                    minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
                Text("💡 在聊天框告诉 Agent：帮我在每天早上9点生成昨日摘要 — Agent 会自动调用 self.trigger add 配置",
                    fontSize = 11.sp, color = ThemeColors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (cronExpr.isNotBlank() && action.isNotBlank()) onConfirm("cron-${(1000..9999).random()}", cronExpr, action) },
                enabled = cronExpr.isNotBlank() && action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand), shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** SCHEDULE 日程触发器 — 简洁输入，让用户找 Agent 配置时间参数。 */
@Composable
fun LifetimeTriggerDialog(
    onDismiss: () -> Unit,
    onConfirm: (id: String, config: String, action: String) -> Unit
) {
    var config by remember { mutableStateOf("08:00-22:00,count=3,interval=60") }
    var action by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SCHEDULE 日程触发器", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                OutlinedTextField(
                    value = config, onValueChange = { config = it },
                    label = { Text("配置参数") },
                    placeholder = { Text("窗口,count=N,interval=M") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md),
                    supportingText = { Text("格式: HH:MM-HH:MM,count=N,interval=M  例: 08:00-22:00,count=3,interval=60", fontSize = 11.sp, color = ThemeColors.textSecondary) }
                )
                OutlinedTextField(
                    value = action, onValueChange = { action = it },
                    label = { Text("执行动作") },
                    placeholder = { Text("描述 Agent 需要执行的任务...") },
                    minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
                Text("💡 在聊天框告诉 Agent：帮我在每天上午10点到下午6点之间找我聊4次天，间隔至少1小时 — Agent 会自动调用 self.trigger add schedule 配置",
                    fontSize = 11.sp, color = ThemeColors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (config.isNotBlank() && action.isNotBlank()) onConfirm("chat-${(1000..9999).random()}", config, action) },
                enabled = config.isNotBlank() && action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand), shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
