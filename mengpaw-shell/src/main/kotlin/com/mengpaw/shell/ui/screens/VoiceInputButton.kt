// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.localization.AppStrings

/** 最小录音时长 (ms) — 更短视为误触丢弃。 */
private const val MIN_RECORD_MS = 300L
/** 上滑取消阈值 (dp) — 手指相对按下点位移。 */
private const val CANCEL_UP_DP = 80f
/** 拖出左边界取消阈值 (dp)。 */
private const val CANCEL_LEFT_DP = 120f

/**
 * 语音按钮 (v0.33.0+) — 透明底线性话筒, 按住录音松开发送。
 *
 * 仅对支持音频输入的模型显示 (能力判定见 VoiceCapability)。
 * 手势: 按住 → 录音 + 计时; 松手 → 发送; 上滑/拖出左边界 → 取消;
 * <300ms → 丢弃 + toast。录音文件直发模型 (input_audio 通道)。
 */
@Composable
fun VoiceInputButton(
    supported: Boolean,
    strings: AppStrings,
    onRecorded: (AttachmentData) -> Unit,
    onRecordStateChanged: (Boolean) -> Unit,
    onElapsed: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder() }
    var pressed by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }

    // 运行时权限 (Manifest 已声明 RECORD_AUDIO)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, strings.voicePermissionDenied, Toast.LENGTH_SHORT).show()
        }
    }
    val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    // 离开组合时强制取消录音
    DisposableEffect(Unit) {
        onDispose { if (recorder.isRecording) recorder.cancel() }
    }

    // 录音计时 tick — 驱动指示条秒数
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs
            onElapsed(elapsed)
            kotlinx.coroutines.delay(100)
        }
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .background(if (pressed) ThemeColors.brand.copy(alpha = 0.12f) else Color.Transparent, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    if (!recorder.start()) {
                        Toast.makeText(context, strings.voicePermissionDenied, Toast.LENGTH_SHORT).show()
                        return@awaitEachGesture
                    }
                    pressed = true
                    recording = true
                    onRecordStateChanged(true)
                    val startX = down.position.x
                    val startY = down.position.y
                    var cancelled = false

                    while (true) {
                        val event = awaitPointerEvent()
                        // 上滑 / 拖出左边界 → 取消
                        val pos = event.changes.firstOrNull() ?: break
                        if (pos.position.y < startY - CANCEL_UP_DP || pos.position.x < startX - CANCEL_LEFT_DP) {
                            cancelled = true
                            onRecordStateChanged(false)
                        }
                        if (pos.changedToUp()) break
                    }

                    pressed = false
                    recording = false
                    onRecordStateChanged(false)

                    if (cancelled) {
                        recorder.cancel()
                        return@awaitEachGesture
                    }
                    if (recorder.elapsedMs < MIN_RECORD_MS) {
                        recorder.cancel()
                        Toast.makeText(context, strings.voiceTooShort, Toast.LENGTH_SHORT).show()
                        return@awaitEachGesture
                    }
                    val duration = recorder.elapsedMs  // stop 前取时长 — stop 后 elapsedMs 归零
                    val file = recorder.stop()
                    if (file != null) {
                        onRecorded(
                            AttachmentData(
                                type = "audio",
                                path = file.absolutePath,
                                mimeType = "audio/mp4",
                                name = file.name,
                                size = file.length(),
                                durationMs = duration,
                                format = "m4a"
                            )
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Mic,
            strings.voiceInput,
            tint = if (pressed) ThemeColors.brand else ThemeColors.textSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}
