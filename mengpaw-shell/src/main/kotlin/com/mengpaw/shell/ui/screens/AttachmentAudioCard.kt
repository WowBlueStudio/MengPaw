// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.kernel.session.AttachmentData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ── 音频卡片 (MediaPlayer 单实例 — 同一时刻只播一条) ────────────────
// 拆自 AttachmentBubbles.kt (2026-08-06, >400 行文件拆分批次4)。

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
