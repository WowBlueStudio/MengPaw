// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.tv

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Text-to-Speech output for TV Agent responses.
 * Uses Android's built-in TTS engine.
 */
class TvTtsOutput(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var initialized = false

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                initialized = true
                onReady()
            }
        }
    }

    /**
     * Speak text immediately, interrupting any current speech.
     */
    fun speak(text: String) {
        if (!initialized) return
        // Strip markdown for clean speech
        val clean = text
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("[*_~`]"), "")
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace("---", "")
            .take(400) // TTS limit
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "mengpaw_tts_${System.currentTimeMillis()}")
    }

    /**
     * Queue text after any current speech finishes.
     */
    fun speakQueued(text: String) {
        if (!initialized) return
        val clean = text.replace(Regex("[*_~`#]"), "").take(400)
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "mengpaw_tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }
}
