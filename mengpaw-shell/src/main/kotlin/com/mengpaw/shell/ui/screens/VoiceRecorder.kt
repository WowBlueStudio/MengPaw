// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.media.MediaRecorder
import com.mengpaw.kernel.DataPaths
import java.io.File

/**
 * MediaRecorder 封装 (v0.33.0+) — 按住说话录音。
 *
 * 输出 m4a (MPEG_4/AAC 44.1kHz 单声道) — OpenAI input_audio 兼容格式, 免转码。
 * 单实例语义: start 后必须 stop/cancel 收尾; 时长用时间差 (MediaRecorder 无录制中时长 API)。
 */
class VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTime: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /** 当前录音时长 (ms) — 仅录制中有效。 */
    val elapsedMs: Long get() = if (recorder != null) System.currentTimeMillis() - startTime else 0L

    /** 开始录音。返回 false = 失败 (被占用/无权限等)。 */
    fun start(): Boolean {
        if (recorder != null) return false
        val dir = File(DataPaths.RECORDINGS)
        dir.mkdirs()
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        var r: MediaRecorder? = null
        return try {
            r = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
            }
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            startTime = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            // P1 修复: 失败路径也要 release, 防止 MediaRecorder 资源泄漏 (录音多次后资源耗尽)
            try { r?.release() } catch (_: Exception) { }
            try { file.delete() } catch (_: Exception) { }
            false
        }
    }

    /** 停止并返回录音文件; 失败/空文件返回 null。 */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            r.release()
            val f = outputFile
            outputFile = null
            if (f != null && f.exists() && f.length() > 0) f else null
        } catch (_: Exception) {
            try { r.release() } catch (_: Exception) { }
            val f = outputFile
            outputFile = null
            try { f?.delete() } catch (_: Exception) { }
            null
        }
    }

    /** 取消录音并删除文件。 */
    fun cancel() {
        val r = recorder
        recorder = null
        try { r?.stop() } catch (_: Exception) { }
        try { r?.release() } catch (_: Exception) { }
        val f = outputFile
        outputFile = null
        try { f?.delete() } catch (_: Exception) { }
    }
}
