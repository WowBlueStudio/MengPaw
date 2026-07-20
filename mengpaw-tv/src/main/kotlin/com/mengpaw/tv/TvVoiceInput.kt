// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Speech recognition wrapper for TV voice input.
 * Uses Android's built-in SpeechRecognizer (works offline on many devices).
 */
class TvVoiceInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    /**
     * Start listening and return recognized text as a Flow.
     * Emits partial results during speech, final result when done.
     */
    fun listen(): Flow<String> = callbackFlow {
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        isListening = true

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend("🎤 正在听...")
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别超时，请重试"
                    SpeechRecognizer.ERROR_NO_MATCH -> "" // silence is ok
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎繁忙"
                    else -> "语音识别错误 ($error)"
                }
                if (msg.isNotEmpty()) trySend(msg)
                close()
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) trySend(text)
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) trySend("...$partial")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        sr.startListening(intent)

        awaitClose {
            sr.destroy()
            recognizer = null
            isListening = false
        }
    }

    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        isListening = false
    }

    fun destroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        isListening = false
    }
}
