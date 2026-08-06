// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.typeFromMime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 附件类型 → 线性图标 (输入栏 chips 行 + 文件卡片共用)。 */
fun attachmentTypeIcon(type: String): ImageVector = when (type) {
    "image" -> Icons.Outlined.Image
    "audio" -> Icons.Outlined.Mic
    "video" -> Icons.Outlined.Videocam
    "document" -> Icons.Outlined.Description
    else -> Icons.Outlined.InsertDriveFile
}

// ── 下行媒体提取: LLM 文本 → 清理后文本 + 附件卡片 ─────────────────
//
// 提取规则 (v0.33.0+):
// 1. `![alt](path)` markdown 图片 → 卡片 (data:/javascript: 前缀排除)
// 2. `[name](path)` 链接且扩展名命中媒体/pdf/doc/xls/zip → 卡片
// 3. `Saved to <path>` / `已保存到 <path>` 行 (render/comfy 插件输出格式) → 卡片 (路径须存在)
// 本地路径必须 exists 才提取 (保守); http(s) URL 按扩展名判定 (无法预验证)

/** 提取媒体引用, 返回 (清理后文本, 卡片列表)。 */
fun extractMedia(content: String): Pair<String, List<AttachmentData>> {
    val cards = mutableListOf<AttachmentData>()
    var text = content

    // 1. ![alt](path)
    text = MARKDOWN_IMAGE_REGEX.replace(text) { m ->
        val path = m.groupValues[1].trim()
        val card = mediaFromMarkdownPath(path) { typeFromMime(null, path) }
        if (card != null) { cards.add(card); "" } else m.value
    }
    // 2. [name](path) — 仅扩展名命中媒体/document 才提取
    //     (审查修复): 本地路径必须 exists 才提取 — 与文件头"保守"规则及分支 1 行为对齐,
    //     否则 ![x](已删除文件.png) 会经链接分支漏出幻影卡片 (UI 渲染坏图)
    text = LINK_REGEX.replace(text) { m ->
        val name = m.groupValues[1].trim()
        val path = m.groupValues[2].trim()
        val ext = path.substringAfterLast('.', "").lowercase()
        val type = typeFromMime(null, path)
        val isRemote = path.startsWith("http://") || path.startsWith("https://")
        val keep = type in setOf("image", "audio", "video", "document") &&
            !path.startsWith("data:") && !path.startsWith("javascript:") &&
            (isRemote || File(path).isFile)
        if (keep && name != path && name.isNotBlank() && name.length <= 60) {
            cards.add(
                AttachmentData(
                    type = type, path = path, name = name,
                    mimeType = mimeForExt(ext)
                )
            ); ""
        } else m.value
    }
    // 3. Saved to / 已保存到 行 (插件输出)
    text = text.lines().joinToString("\n") { line ->
        val m = SAVED_TO_REGEX.find(line) ?: return@joinToString line
        val path = m.groupValues[1].trim()
        val card = mediaFromMarkdownPath(path) { typeFromMime(null, path) }
        if (card != null) { cards.add(card); "" } else line
    }
    return text to cards
}

/** markdown 图片/路径 → 附件卡片; 本地路径须存在, URL 按扩展名。 */
private fun mediaFromMarkdownPath(path: String, typeOf: (String) -> String): AttachmentData? {
    val trimmed = path.trim().removePrefix("file://")
    if (trimmed.startsWith("data:") || trimmed.startsWith("javascript:") || trimmed.isBlank()) return null
    val isRemote = trimmed.startsWith("http://") || trimmed.startsWith("https://")
    if (!isRemote) {
        val f = File(trimmed)
        if (!f.exists() || !f.isFile) return null
    }
    val type = typeOf(trimmed)
    if (type !in setOf("image", "audio", "video", "document")) return null
    return AttachmentData(
        type = type, path = trimmed, name = trimmed.substringAfterLast('/'),
        mimeType = mimeForExt(trimmed.substringAfterLast('.', "").lowercase()),
        size = if (isRemote) 0L else File(trimmed).length()
    )
}

private val LINK_REGEX = Regex("\\[([^\\]]{1,80})]\\(([^)]{1,500})\\)")
private val SAVED_TO_REGEX = Regex("(?:Saved to|已保存到)\\s+(\\S+)\\s*$")

private fun mimeForExt(ext: String): String = when (ext) {
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
    "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"
    "pdf" -> "application/pdf"; "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "zip" -> "application/zip"; "txt" -> "text/plain"; "md" -> "text/markdown"
    else -> "application/octet-stream"
}

// ── 附件卡片列表 (气泡内垂直堆叠) ──────────────────────────────────

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

// ── 音频卡片 (MediaPlayer 单实例 — 同一时刻只播一条) ────────────────

internal object AudioPlayerHolder {
    private var player: MediaPlayer? = null
    @Volatile var currentPath: String? = null; private set
    @Volatile var durationMs: Int = 0; private set

    /** 播放/暂停切换; 返回是否处于播放态。 */
    fun toggle(path: String): Boolean {
        synchronized(this) {
            if (currentPath == path && player?.isPlaying == true) {
                player?.pause()
                return false
            }
            stopInternal()
            val mp = try {
                MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    durationMs = duration
                }
            } catch (_: Exception) { return false }
            mp.start()
            player = mp
            currentPath = path
            return true
        }
    }

    fun isPlaying(path: String): Boolean = synchronized(this) {
        currentPath == path && player?.isPlaying == true
    }

    fun currentPosition(path: String): Int = synchronized(this) {
        if (currentPath == path) try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 } else 0
    }

    fun stop(path: String? = null) = synchronized(this) {
        if (path == null || path == currentPath) stopInternal()
    }

    private fun stopInternal() {
        try { player?.stop(); player?.release() } catch (_: Exception) { }
        player = null
        currentPath = null
    }
}

@Composable
fun AudioAttachmentCard(att: AttachmentData, isUserSide: Boolean) {
    val context = LocalContext.current
    var localPath by remember(att.path) { mutableStateOf(if (att.path.startsWith("http")) "" else att.path) }
    LaunchedEffect(att.path) {
        if (att.path.startsWith("http") && localPath.isEmpty()) {
            localPath = withContext(Dispatchers.IO) { downloadToCache(context, att.path) } ?: ""
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    // 进度轮询 (播放中每 500ms)
    LaunchedEffect(isPlaying, localPath) {
        while (isPlaying && localPath.isNotEmpty()) {
            val pos = AudioPlayerHolder.currentPosition(localPath)
            val dur = AudioPlayerHolder.durationMs.coerceAtLeast(1)
            progress = pos.toFloat() / dur
            if (!AudioPlayerHolder.isPlaying(localPath)) { isPlaying = false; break }
            delay(500)
        }
    }
    // 离开组合时停止
    DisposableEffect(Unit) {
        onDispose { if (localPath.isNotEmpty()) AudioPlayerHolder.stop(localPath) }
    }

    val fg = if (isUserSide) Color.White else ThemeColors.textPrimary
    val waveHeights = remember(att.path) { List(18) { (8 + kotlin.random.Random.nextInt(20)).dp } }

    Row(
        Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(ArcoRadius.md))
            .background(if (isUserSide) Color.White.copy(alpha = 0.15f) else ThemeColors.bgCardHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 播放/暂停
        Box(Modifier.size(32.dp).background(if (isUserSide) Color.White.copy(alpha = 0.25f) else ThemeColors.brand.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = localPath.isNotEmpty()) {
                isPlaying = if (isPlaying) {
                    AudioPlayerHolder.stop(localPath); false
                } else {
                    AudioPlayerHolder.toggle(localPath)
                }
            }, contentAlignment = Alignment.Center) {
            if (isPlaying) {
                Text("❚❚", color = fg, fontSize = 10.sp)
            } else {
                Icon(Icons.Filled.PlayArrow, "播放", tint = fg, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        // 装饰波形 (静态, 无解码库不做真实波形)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(Modifier.height(22.dp), verticalAlignment = Alignment.Bottom) {
                waveHeights.forEach { h -> Box(Modifier.width(3.dp).height(h).padding(end = 1.dp)
                    .background(fg.copy(alpha = if (isPlaying) 0.9f else 0.4f), RoundedCornerShape(1.dp))) }
            }
            Spacer(Modifier.height(3.dp))
            // 进度条
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (isUserSide) Color.White else ThemeColors.brand,
                trackColor = fg.copy(alpha = 0.15f)
            )
        }
        Spacer(Modifier.width(8.dp))
        val dur = att.durationMs
        Text(if (dur > 0) "${(dur / 1000)}″" else "",
            style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
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
                    } catch (_: Exception) { }
                } else if (att.path.startsWith("http")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(att.path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
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

/** md 文件预览 Dialog — MarkdownText 渲染 (v0.34.0+), 200K 字符截断防卡 UI。
 *  FIX(闪退): Dialog 高度约束无限, fillMaxHeight(fraction) 无效 → 滚动容器收到 ∞ 即崩;
 *  改用 heightIn(max=屏高×比例) 提供有界高度 + weight(1f) 滚动区。 */
@Composable
private fun MdPreviewDialog(path: String, name: String, onDismiss: () -> Unit) {
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
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
                    try { File(path).readText().take(200_000) } catch (_: Exception) { "无法读取文件" }
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

// ── 网络下载缓存 (手写 HttpURLConnection, 零依赖) ──────────────────

private fun downloadToCache(context: android.content.Context, url: String): String? {
    return try {
        val dir = File(context.cacheDir, "media_cache")
        dir.mkdirs()
        val ext = url.substringAfterLast('.', "").take(5).filter { it.isLetterOrDigit() }
        val fileName = sha1(url) + if (ext.isNotBlank()) ".$ext" else ".bin"
        val file = File(dir, fileName)
        if (file.exists() && file.length() > 0) return file.absolutePath
        val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 MengPaw/0.33")
        }
        conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output, 64 * 1024) } }
        conn.disconnect()
        if (file.exists() && file.length() > 0) file.absolutePath else null
    } catch (_: Exception) { null }
}

private fun sha1(s: String): String =
    java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
        .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
