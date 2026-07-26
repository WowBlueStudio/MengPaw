// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.util.downloadImage
import com.mengpaw.design.theme.ThemeColors

/** Image picker dialog listing detected images with download-on-click. */
@Composable
fun BrowserImagePickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    images: List<DetectedImage>,
    ctx: Context
) {
    if (!visible || images.isEmpty()) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检测到 ${images.size} 个图片 (顶层→底层)") },
        text = {
            LazyColumn {
                items(images.size) { idx ->
                    val img = images[idx]
                    val imgScope = rememberCoroutineScope()
                    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        downloadImage(imgScope, ctx, img.src)
                        Toast.makeText(ctx, "已保存: ${img.src.substringAfterLast('/').take(30)}", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }, shape = RoundedCornerShape(8.dp), color = ThemeColors.bgCardHigh) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = ThemeColors.brand, modifier = Modifier.width(28.dp), fontSize = 12.sp)
                            Column(Modifier.weight(1f)) {
                                Text(img.alt.ifBlank { img.src.substringAfterLast('/').take(40) }, maxLines = 1, fontSize = 13.sp)
                                Text(img.src.take(50), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            Icon(Icons.Default.Add, "保存", tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
