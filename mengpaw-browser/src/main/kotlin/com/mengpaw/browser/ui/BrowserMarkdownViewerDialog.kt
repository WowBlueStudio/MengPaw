// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.design.components.MarkdownText

/** Markdown viewer dialog for .md file preview. */
@Composable
fun BrowserMarkdownViewerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: String
) {
    if (!visible || content.isBlank()) return

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Markdown 预览", fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MarkdownText(content = content)
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
