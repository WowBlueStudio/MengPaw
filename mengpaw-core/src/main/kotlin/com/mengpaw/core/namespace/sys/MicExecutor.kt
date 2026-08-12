// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.media.MediaRecorder
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * sys.mic.* — 麦克风录音 (对齐 Termux:API termux-microphone-record)。
 *
 * 需要 RECORD_AUDIO 权限; 输出 m4a 到公共输出目录 DataPaths.OUTPUT。
 * 异步模式: sys.mic.record 立即返回并自动停止 (默认 30s, 最长 600s),
 * sys.mic.stop 可提前停止; 单实例互斥。
 */
internal object MicExecutor {

    private val recLock = Any()
    private var recorder: MediaRecorder? = null
    private var outputPath: String? = null
    private var autoStopJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun record(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        if (!app.checkSelf(Manifest.permission.RECORD_AUDIO)) {
            return ExecutionResult.fail(
                "需要 RECORD_AUDIO 权限。请先执行 sys.permission.request RECORD_AUDIO",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        val seconds = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 600) ?: 30
        synchronized(recLock) {
            if (recorder != null) {
                return ExecutionResult.fail("已有录音在途。请先执行 sys.mic.stop", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            val dir = File(DataPaths.OUTPUT)
            try { dir.mkdirs() } catch (_: Exception) {}
            val path = File(dir, "mic_${System.currentTimeMillis()}.m4a").absolutePath
            val rec = try {
                MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(path)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                return ExecutionResult.fail("录音启动失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            recorder = rec
            outputPath = path
            autoStopJob?.cancel()
            autoStopJob = scope.launch {
                delay(seconds * 1000L)
                stopInternal()
            }
        }
        return ExecutionResult.ok("已开始录音 ($seconds 秒自动停止)。文件: $outputPath\n提前停止: sys.mic.stop")
    }

    suspend fun stop(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val stopped = stopInternal()
        if (stopped == null) {
            return ExecutionResult.fail("当前没有进行中的录音", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        return ExecutionResult.ok("录音已停止并保存: $stopped")
    }

    private fun stopInternal(): String? {
        return synchronized(recLock) {
            val rec = recorder ?: return@synchronized null
            val path = outputPath
            recorder = null
            outputPath = null
            autoStopJob?.cancel()
            autoStopJob = null
            try {
                rec.stop()
                rec.release()
            } catch (_: Exception) {
                try { rec.release() } catch (_: Exception) {}
            }
            path
        }
    }
}
