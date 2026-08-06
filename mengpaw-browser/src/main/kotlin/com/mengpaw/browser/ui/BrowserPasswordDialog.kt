// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.design.theme.ThemeColors

/** Password management dialog with save toggle and clear button. */
@Composable
fun BrowserPasswordDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    prefs: BrowserPrefs
) {
    if (!visible) return

    val ctx = LocalContext.current
    val pwdDb = remember { android.webkit.WebViewDatabase.getInstance(ctx) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("密码管理 Passwords") },
        text = {
            Column {
                // P2 fix: 原 "保存密码" Switch 是只写不读的假开关 — WebView 自 API 18 移除
                // setSavePassword, 该开关写入的 pref 从不生效。改为固定说明, 不再提供无效开关。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("保存密码 — 已停用", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // 诚实说明: 本应用不实现密码自动填充/长按保存 — 登录凭据由系统密码管理器负责
                Text("登录凭据由系统密码管理器负责保存与填充，本应用不存储、不读取密码内容。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    // P0 修复: clearUsernamePassword 在 API 33+ 已从框架移除（类上方法直接消失），
                    // 调用即抛 NoSuchMethodError（Error 而非 Exception，必崩）— 参考 capturePicture 教训。
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        Toast.makeText(ctx, "Android 13+ 已移除 WebView 密码库，密码由系统密码管理器管理，无需清除", Toast.LENGTH_LONG).show()
                    } else {
                        pwdDb.clearUsernamePassword()
                        Toast.makeText(ctx, "已清除所有密码", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("清除所有密码") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
