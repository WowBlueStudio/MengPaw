// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * sys.stt.* — 语音转文字 (对齐 Termux:API termux-speech-to-text)。
 *
 * 走 RecognizerIntent + ActivityResultLauncher 桥 (SysExecutor.requestSpeech):
 * 需要前台 Activity 与 RECORD_AUDIO 权限; 命令挂起等待识别结果 (60s 超时)。
 */
internal object SpeechExecutor {

    /** 发起系统语音识别并挂起等待; 返回识别文本或 null (取消/超时/无权限/无前台)。 */
    internal suspend fun speechToText(activity: Activity, prompt: String, timeoutMs: Long): String? {
        val app = SysExecutor.appContext ?: return null
        if (!app.checkSelf(Manifest.permission.RECORD_AUDIO)) return null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        val pair = SysExecutor.requestSpeech(intent, timeoutMs) ?: return null
        if (pair.first != Activity.RESULT_OK) return null
        return try {
            pair.second?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun sttListen(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val activity = SysExecutor.currentActivity?.get()
            ?: return ExecutionResult.fail("当前无前台 Activity, 无法发起语音识别", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        if (!app.checkSelf(Manifest.permission.RECORD_AUDIO)) {
            return ExecutionResult.fail(
                "需要 RECORD_AUDIO 权限。请先执行 sys.permission.request RECORD_AUDIO",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        val prompt = args.firstOrNull() ?: "请说出要转写的内容"
        val text = speechToText(activity, prompt, 60_000L)
        return if (text == null) {
            ExecutionResult.fail("语音识别取消/超时/无结果", errorCode = ErrorCodes.ERR_TIMEOUT)
        } else {
            ExecutionResult.ok("text: $text")
        }
    }
}
