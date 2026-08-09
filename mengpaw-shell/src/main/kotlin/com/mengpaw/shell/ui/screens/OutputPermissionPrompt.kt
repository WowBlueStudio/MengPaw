// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.kernel.DataPaths
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * 输出目录授权引导 (v0.35.1) — 启动时公共目录 /MengPaw/ 不可写 (Android 11+
 * 未授予 MANAGE_EXTERNAL_STORAGE) 则弹窗, 引导跳『所有文件访问』设置页;
 * 授权返回后 MainActivity.onResume refreshOutput, 输出目录切公共, 引导自动消失。
 */
@Composable
fun OutputPermissionPrompt(strings: AppStrings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.outputPermissionTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "${strings.outputPermissionBody}\n\n${strings.outputDirCurrent}: ${DataPaths.OUTPUT}",
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } catch (_: Exception) {}
                },
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text(strings.outputPermissionGrant, color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.outputPermissionLater) }
        }
    )
}

/** 是否需要弹授权引导 — Android 11+ 未授权 且 输出目录回退到私有路径。 */
fun needsOutputPermission(): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 30) return false
    if (android.os.Environment.isExternalStorageManager()) return false
    val publicOut = try {
        java.io.File(android.os.Environment.getExternalStorageDirectory(), "MengPaw").absolutePath
    } catch (_: Exception) { return false }
    return DataPaths.OUTPUT != publicOut
}
