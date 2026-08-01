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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("保存密码")
                    Switch(checked = prefs.savePasswords, onCheckedChange = { prefs.savePasswords = it })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("已保存的密码会在登录时自动填充。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                Text("长按页面中的登录表单可以选择保存凭据。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    pwdDb.clearUsernamePassword()
                    Toast.makeText(ctx, "已清除所有密码", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("清除所有密码") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
