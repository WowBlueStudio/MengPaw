// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * sys.tts.* — 文字转语音朗读 (对齐 Termux:API termux-tts-speak / termux-tts-engines)。
 *
 * 无需运行时权限; speak 为挂起式, 等待朗读完成 (最长 120s) 后返回。
 */
internal object TtsExecutor {

    suspend fun ttsSpeak(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val text = args.joinToString(" ") { it.trim() }.trim()
        if (text.isEmpty()) {
            return ExecutionResult.fail(
                "Usage: sys.tts.speak <文本> [lang:zh-CN]",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val lang = args.lastOrNull()
            ?.takeIf { it.startsWith("lang:") }
            ?.substringAfter(":")
        val ready = CompletableDeferred<Boolean>()
        val spoken = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(app) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        return try {
            val okInit = withTimeoutOrNull(10_000L) { ready.await() } ?: false
            if (!okInit) {
                return ExecutionResult.fail("TTS 初始化失败 (设备可能无语音引擎)", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            if (lang != null) {
                tts.setLanguage(Locale.forLanguageTag(lang))
            } else {
                tts.setLanguage(Locale.getDefault())
            }
            val utteranceId = "mengpaw_tts_${System.currentTimeMillis()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) spoken.complete(true)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) spoken.complete(false)
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) spoken.complete(false)
                }
            })
            val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued != TextToSpeech.SUCCESS) {
                return ExecutionResult.fail("TTS 朗读失败 (错误码 $queued)", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            val okSpoken = withTimeoutOrNull(120_000L) { spoken.await() } ?: false
            if (!okSpoken) {
                return ExecutionResult.fail("TTS 朗读超时或失败", errorCode = ErrorCodes.ERR_TIMEOUT)
            }
            ExecutionResult.ok("tts: 已朗读")
        } catch (e: Exception) {
            ExecutionResult.fail("TTS 异常: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        } finally {
            try { tts.shutdown() } catch (_: Exception) {}
        }
    }

    suspend fun ttsEngines(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        return try {
            val engines = TextToSpeech(app, null).engines
            val lines = engines.map { "${it.name} | ${it.label}" }
            ExecutionResult.ok(if (lines.isEmpty()) "(无 TTS 引擎)" else lines.joinToString("\n"))
        } catch (e: Exception) {
            ExecutionResult.fail("获取 TTS 引擎失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
