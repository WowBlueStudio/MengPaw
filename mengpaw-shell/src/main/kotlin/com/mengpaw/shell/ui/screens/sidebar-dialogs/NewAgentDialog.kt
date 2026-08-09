// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * P1 修复: 工作区文件夹名消毒 — 替换路径分隔符, 拒绝穿越段。
 * 规则对齐 DataPaths.safeAgentDir ([/\\] → _), 中文等非分隔符字符保留。
 */
private fun sanitizeFolderName(raw: String): String {
    val cleaned = raw.replace(Regex("[/\\\\]"), "_").trim()
    return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "agent" else cleaned
}

@Composable
fun NewAgentDialog(
    strings: AppStrings,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (NewAgentForm) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var workspaceFolder by remember { mutableStateOf("") }
    var intro by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.brandContainer,
                    modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonAdd, null, Modifier.size(22.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.width(ArcoSpacing.md))
                Text(strings.newAgentTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ArcoSpacing.md)) {
                OutlinedTextField(value = name, onValueChange = { name = it; if (workspaceFolder.isBlank()) workspaceFolder = it },
                    label = { Text(strings.newAgentNameLabel) }, placeholder = { Text(strings.newAgentNamePlaceholder) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md),
                    leadingIcon = { Icon(Icons.Outlined.PersonAdd, null, tint = ThemeColors.textSecondary) })
                OutlinedTextField(value = workspaceFolder, onValueChange = { workspaceFolder = it },
                    label = { Text(strings.newAgentFolderLabel) }, placeholder = { Text(strings.newAgentFolderPlaceholder) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md),
                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, tint = ThemeColors.textSecondary) },
                    supportingText = {
                        val full = "${com.mengpaw.kernel.DataPaths.AGENTS}/${workspaceFolder.ifBlank { name }}"
                        Text(String.format(strings.newAgentFolderHint, full.replaceFirst(Regex("^/data/data/"), "./")),
                            fontSize = 10.sp, color = ThemeColors.textSecondary)
                    })
                OutlinedTextField(value = intro, onValueChange = { intro = it },
                    label = { Text(strings.newAgentIntroLabel) }, placeholder = { Text(strings.newAgentIntroPlaceholder) },
                    minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md),
                    leadingIcon = { Icon(Icons.Outlined.Description, null, tint = ThemeColors.textSecondary) })
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(strings.cancel) }
                Spacer(Modifier.weight(1f))
                Button(
                    // P1 修复: workspaceFolder 可能含路径分隔符/穿越段 — 保存前消毒
                    onClick = { if (name.isNotBlank()) onConfirm(NewAgentForm(name = name.trim(), workspaceFolder = sanitizeFolderName(workspaceFolder.ifBlank { name }), intro = intro.trim())) },
                    enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                    shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm)
                ) { Text(strings.newAgentCreate, color = Color.White) }
            }
        }
    )
}
