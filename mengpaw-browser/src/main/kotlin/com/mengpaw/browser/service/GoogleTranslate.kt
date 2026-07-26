// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free Google Translate client using the public web endpoint.
 * No API key required — uses the same backend as translate.google.com.
 * For higher-volume/critical use, switch to the official Cloud Translation API.
 */
object GoogleTranslate {
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    /** Common target language codes. */
    val LANGUAGES = mapOf(
        "中文(简)" to "zh-CN", "中文(繁)" to "zh-TW", "English" to "en",
        "日本語" to "ja", "한국어" to "ko", "Français" to "fr",
        "Deutsch" to "de", "Español" to "es", "Português" to "pt",
        "Italiano" to "it", "Русский" to "ru", "العربية" to "ar",
        "हिन्दी" to "hi", "ไทย" to "th", "Tiếng Việt" to "vi",
        "Bahasa Indonesia" to "id", "Türkçe" to "tr"
    )

    /** Translate text using the free public endpoint. */
    suspend fun translate(text: String, targetLang: String, sourceLang: String = "auto"): String {
        return withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "$ENDPOINT?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val raw = try { conn.inputStream.bufferedReader().readText() } catch (e: Exception) { conn.disconnect(); "" }
            conn.disconnect()
            parseResult(raw)
        }
    }

    /** Parse Google's JSON response: [[["translated","orig",...]],null,"en"] */
    private fun parseResult(json: String): String {
        return try {
            // Remove the outer array wrapper, extract first string from each sentence
            val sb = StringBuilder()
            // Simple parser: find all ["translated","original",...] patterns
            val sentenceRegex = Regex("""\[\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"""")
            sentenceRegex.findAll(json).forEach { match ->
                val translated = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\")
                sb.append(translated)
            }
            sb.toString().ifBlank { "(translation empty)" }
        } catch (e: Exception) {
            "(translation failed: ${e.message})"
        }
    }
}
