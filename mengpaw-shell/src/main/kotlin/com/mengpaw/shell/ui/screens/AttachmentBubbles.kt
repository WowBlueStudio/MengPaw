// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.kernel.session.AttachmentData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ── 附件卡片 (气泡内垂直堆叠) ──
// 媒体提取逻辑 → AttachmentMediaExtractor.kt; 下载缓存 → AttachmentMediaDownloader.kt;
// 音频卡片 → AttachmentAudioCard.kt; 全屏预览 Dialog → AttachmentPreviewDialogs.kt

/** 附件类型 → 线性图标 (输入栏 chips 行 + 文件卡片共用)。 */
fun attachmentTypeIcon(type: String): ImageVector = when (type) {
    "image" -> Icons.Outlined.Image
    "audio" -> Icons.Outlined.Mic
    "video" -> Icons.Outlined.Videocam
    "document" -> Icons.Outlined.Description
    else -> Icons.Outlined.InsertDriveFile
}

@Composable
fun AttachmentCardList(attachments: List<AttachmentData>, isUserSide: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { att ->
            when (att.type) {
                "image" -> ImageAttachmentCard(att, isUserSide)
                "audio" -> AudioAttachmentCard(att, isUserSide)
                "video" -> VideoAttachmentCard(att, isUserSide)
                else -> FileAttachmentCard(att, isUserSide)
            }
        }
    }
}

/** 图片解码: 先读边界算 inSampleSize (目标 ≤maxDim) — 防大图 OOM。
 *  v0.34.0: internal — PendingAttachmentsBar 缩略图 (maxDim=512) 复用。 */
internal fun decodeSampled(path: String, maxDim: Int = 2048): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }
}

// ── 图片卡片 + 全屏预览 ────────────────────────────────────────────

@Composable
fun ImageAttachmentCard(att: AttachmentData, isUserSide: Boolean) {
    val context = LocalContext.current
    var localPath by remember(att.path) { mutableStateOf(if (att.path.startsWith("http")) "" else att.path) }
    var failed by remember(att.path) { mutableStateOf(false) }
    var retryKey by remember(att.path) { mutableStateOf(0) }
    // 网络图片 → 下载到 cacheDir 缓存 (retryKey 变化触发重试)
    LaunchedEffect(att.path, retryKey) {
        if (att.path.startsWith("http") && localPath.isEmpty()) {
            failed = false
            val cached = withContext(Dispatchers.IO) { downloadToCache(context, att.path) }
            if (cached != null) localPath = cached else failed = true
        }
    }
    val bitmap = remember(localPath) { if (localPath.isNotEmpty()) decodeSampled(localPath) else null }
    var showPreview by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ArcoRadius.md)
    val bg = if (isUserSide) Color.White.copy(alpha = 0.15f) else ThemeColors.bgCardHigh

    Box(
        // v0.34.0+: 图片占 100% 气泡宽度, 高度按原比例浮动 (aspectRatio), 不截断不露底色
        Modifier.fillMaxWidth().clip(shape).background(bg)
            .clickable(enabled = bitmap != null) { showPreview = true }
    ) {
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), att.name, Modifier.fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()))
            failed -> PlaceholderText("图片加载失败，点击重试") { retryKey++ }
            else -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ThemeColors.textSecondary)
            }
        }
    }
    if (showPreview && localPath.isNotEmpty()) {
        MediaPreviewDialog(path = localPath, name = att.name, onDismiss = { showPreview = false })
    }
}

@Composable
private fun PlaceholderText(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 180.dp, height = 120.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
    }
}

// ── 视频卡片 + 全屏播放 Dialog ─────────────────────────────────────

@Composable
fun VideoAttachmentCard(att: AttachmentData, isUserSide: Boolean) {
    val context = LocalContext.current
    var localPath by remember(att.path) { mutableStateOf(if (att.path.startsWith("http")) "" else att.path) }
    LaunchedEffect(att.path) {
        if (att.path.startsWith("http") && localPath.isEmpty()) {
            localPath = withContext(Dispatchers.IO) { downloadToCache(context, att.path) } ?: ""
        }
    }
    // 封面帧 (MediaMetadataRetriever, framework API 零依赖)
    // 注意: 不用 use{} — MediaMetadataRetriever 仅 API 29+ 实现 AutoCloseable, 手动 release
    val frame = remember(localPath) {
        if (localPath.isEmpty()) null else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(localPath)
                retriever.getFrameAtTime(-1)
            } catch (_: Exception) { null }
            finally { try { retriever.release() } catch (_: Exception) { } }
        }
    }
    var showPlayback by remember { mutableStateOf(false) }

    Box(
        Modifier.widthIn(max = 260.dp).heightIn(min = 120.dp)
            .clip(RoundedCornerShape(ArcoRadius.md))
            .background(if (isUserSide) Color.White.copy(alpha = 0.15f) else ThemeColors.bgCardHigh)
            .clickable(enabled = localPath.isNotEmpty()) { showPlayback = true },
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(frame.asImageBitmap(), att.name, Modifier.fillMaxWidth().heightIn(max = 200.dp))
        } else {
            Icon(attachmentTypeIcon("video"), null, tint = ThemeColors.textSecondary, modifier = Modifier.size(40.dp))
        }
        // 中央播放三角
        Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
    if (showPlayback && localPath.isNotEmpty()) {
        VideoPlaybackDialog(path = localPath, name = att.name, onDismiss = { showPlayback = false })
    }
}

// ── 文件/文档卡片 ──────────────────────────────────────────────────
// v0.34.0+: 仅显示文件名 (含扩展名) + 文件大小, 其余信息不显示; 点击打开保留

@Composable
fun FileAttachmentCard(att: AttachmentData, isUserSide: Boolean) {
    val context = LocalContext.current
    val fg = if (isUserSide) Color.White else ThemeColors.textPrimary
    val ext = att.path.substringAfterLast('.', "").lowercase()
    val sizeLabel = when {
        att.size >= 1024 * 1024 -> "%.1f MB".format(att.size / 1024.0 / 1024.0)
        att.size >= 1024 -> "%.0f KB".format(att.size / 1024.0)
        att.size > 0 -> "$att.size B"
        else -> ""
    }

    var showMdPreview by remember(att.path) { mutableStateOf(false) }
    Row(
        Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(ArcoRadius.md))
            .background(if (isUserSide) Color.White.copy(alpha = 0.15f) else ThemeColors.bgCardHigh)
            .clickable {
                // v0.34.0+: md/markdown 文件 → 内置 MarkdownText 预览; 其余 → ACTION_VIEW
                val isMarkdown = ext == "md" || ext == "markdown"
                val file = File(att.path)
                if (isMarkdown && file.exists()) {
                    showMdPreview = true
                    return@clickable
                }
                // ACTION_VIEW + FileProvider (对齐 ClipboardIntentExecutor.intentView)
                if (file.exists()) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, att.mimeType.ifBlank { mimeForExt(ext) })
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // v0.34.3: 不再静默吞异常 — FileProvider 未映射/无处理应用时用户能看到原因
                        android.widget.Toast.makeText(
                            context, "无法打开文件: ${e.message?.take(60) ?: att.name}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else if (att.path.startsWith("http")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(att.path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context, "无法打开链接: ${e.message?.take(60) ?: att.path}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // v0.34.3: 文件不存在 — 明确提示 (此前静默, 用户以为点击坏了)
                    android.widget.Toast.makeText(
                        context, "文件不存在: ${att.path.take(80)}", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(att.name.ifBlank { att.path.substringAfterLast('/') },
                style = MaterialTheme.typography.bodySmall.copy(color = fg),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sizeLabel.isNotBlank()) {
                Text(sizeLabel, style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.6f))
            }
        }
    }
    if (showMdPreview) {
        MdPreviewDialog(path = att.path, name = att.name, onDismiss = { showMdPreview = false })
    }
}
