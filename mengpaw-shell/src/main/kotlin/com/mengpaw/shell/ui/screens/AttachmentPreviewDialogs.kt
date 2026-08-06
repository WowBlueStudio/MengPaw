// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

// ── 附件全屏预览 Dialog — 拆自 AttachmentBubbles.kt (2026-08-06, 批次4) ──

/** 全屏图片预览 Dialog (对齐 BrowserScreen.ImagePreview 思路, Dialog 形态)。 */
@Composable
fun MediaPreviewDialog(path: String, name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(path) { decodeSampled(path, maxDim = 4096) }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), name, Modifier.fillMaxWidth())
            } else {
                Text("无法加载图片", color = Color.White)
            }
            // 右上角关闭
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** 全屏视频播放 Dialog (AndroidView 包 VideoView, 零依赖)。 */
@Composable
fun VideoPlaybackDialog(path: String, name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        // P1 修复: 持有 VideoView 引用, 关闭/销毁时 stopPlayback (内部释放 MediaPlayer)。
        // 声明在 Dialog 层 (Box 外) — DisposableEffect 同层使用, 作用域一致。
        val videoView = remember { mutableStateOf<VideoView?>(null) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f))) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(path)
                        setOnPreparedListener { it.isLooping = false; it.start() }
                    }.also { videoView.value = it }
                },
                modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
                update = { }
            )
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        // 对话框离开组合 (关闭/销毁) 时释放 MediaPlayer — 不释放则音频继续响
        DisposableEffect(Unit) {
            onDispose {
                try { videoView.value?.stopPlayback() } catch (_: Exception) { }
                videoView.value = null
            }
        }
    }
}

/** md 文件预览 Dialog — MarkdownText 渲染 (v0.34.0+), 200K 字符截断防卡 UI。
 *  FIX(闪退): Dialog 高度约束无限, fillMaxHeight(fraction) 无效 → 滚动容器收到 ∞ 即崩;
 *  改用 heightIn(max=屏高×比例) 提供有界高度 + weight(1f) 滚动区。 */
@Composable
internal fun MdPreviewDialog(path: String, name: String, onDismiss: () -> Unit) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg),
            color = ThemeColors.bgCardHigh,
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = screenH * 0.85f)
        ) {
            Column {
                // 标题行: 文件名 + 关闭
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall,
                        color = ThemeColors.textPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭", tint = ThemeColors.textSecondary)
                    }
                }
                HorizontalDivider(color = ThemeColors.border)
                val content = remember(path) {
                    try { java.io.File(path).readText().take(200_000) } catch (_: Exception) { "无法读取文件" }
                }
                Box(Modifier.fillMaxHeight().weight(1f)) {
                    SelectionContainer {
                        com.mengpaw.design.components.MarkdownText(
                            content = content,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(ArcoSpacing.lg),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary),
                            nestedScroll = true
                        )
                    }
                }
            }
        }
    }
}
