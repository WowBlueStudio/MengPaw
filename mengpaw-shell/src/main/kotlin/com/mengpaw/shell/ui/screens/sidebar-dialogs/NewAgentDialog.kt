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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

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
        title = { Text(strings.newAgentTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                OutlinedTextField(value = name, onValueChange = { name = it; if (workspaceFolder.isBlank()) workspaceFolder = it },
                    label = { Text(strings.newAgentNameLabel) }, placeholder = { Text(strings.newAgentNamePlaceholder) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md))
                OutlinedTextField(value = workspaceFolder, onValueChange = { workspaceFolder = it },
                    label = { Text(strings.newAgentFolderLabel) }, placeholder = { Text(strings.newAgentFolderPlaceholder) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md),
                    supportingText = { Text(String.format(strings.newAgentFolderHint, "${com.mengpaw.kernel.DataPaths.AGENTS}/${workspaceFolder.ifBlank { name }}"), fontSize = 10.sp, color = ThemeColors.textSecondary) })
                OutlinedTextField(value = intro, onValueChange = { intro = it },
                    label = { Text(strings.newAgentIntroLabel) }, placeholder = { Text(strings.newAgentIntroPlaceholder) },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md))
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(NewAgentForm(name = name.trim(), workspaceFolder = workspaceFolder.ifBlank { name }.trim(), intro = intro.trim())) },
                enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text(strings.newAgentCreate, color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}
