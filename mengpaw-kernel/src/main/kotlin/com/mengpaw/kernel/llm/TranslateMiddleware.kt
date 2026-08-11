// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Token-saving translation layer for English-optimized models.
 *
 * Flow: User Chinese → translate(zh→en) → Model (English) → translate(en→zh) → User Chinese
 *
 * English tokens are ~1/3 as expensive as Chinese tokens for most models.
 * This middleware transparently handles translation, reducing cost by ~30-60%.
 *
 * ## Usage
 * ```kotlin
 * val middleware = TranslateMiddleware()
 * val englishInput = middleware.toEnglish("帮我查一下今天天气")
 * // → "Help me check today's weather"
 *
 * val chineseOutput = middleware.toChinese("Today will be sunny, 18-25°C")
 * // → "今天晴，18-25°C"
 * ```
 */
class TranslateMiddleware {

    /** Whether auto-translation is enabled. 默认关闭 — 仅用户主动开启时才启用 (opt-in). */
    var enabled = false

    /** Models that benefit from translation (English-optimized). */
    private val englishModels = setOf(
        "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo",
        "grok-2", "grok-2-vision", "grok-3",
        "claude-",  // prefix match for Claude models
    )

    /** Check if a model would benefit from translation. */
    fun shouldTranslate(model: String): Boolean =
        enabled && englishModels.any { model.contains(it, ignoreCase = true) }

    /**
     * Translate Chinese text to English.
     * Returns original text if translation fails or text is already mostly English.
     */
    suspend fun toEnglish(text: String): String {
        if (!needsTranslation(text, "zh")) return text
        return translate(text, "zh", "en")
    }

    /**
     * Translate English text to Chinese.
     * Returns original text if translation fails.
     */
    suspend fun toChinese(text: String): String {
        if (!needsTranslation(text, "en")) return text
        return translate(text, "en", "zh-CN")
    }

    /**
     * 通用翻译 — 任意语言对 (Google 免费接口, [from] 默认 auto 自动检测源语言)。
     * 失败时回退返回原文 (调用方据此判断是否走 LLM 回退)。
     */
    suspend fun translate(text: String, from: String = "auto", to: String): String {
        if (text.isBlank()) return text
        return translateInternal(text, from, to)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun needsTranslation(text: String, fromLang: String): Boolean {
        if (text.length < 10) return false
        val cjk = text.count { it in '一'..'鿿' || it in '぀'..'ヿ' }
        val ascii = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return when (fromLang) {
            "zh" -> cjk > ascii // mostly Chinese → needs translation
            "en" -> ascii > text.length * 0.6 // mostly English → translate to Chinese
            else -> false
        }
    }

    /**
     * Translate text using Google's free public Translate API.
     * No API key required. Rate-limited to ~100 req/min.
     */
    private suspend fun translateInternal(text: String, from: String, to: String): String =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(text.take(1500), "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=$encoded"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val raw = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                parseGoogleResult(raw)
            } catch (_: Exception) {
                text // Fallback: return original text
            }
        }

    /** Parse Google's response: [[["translated","orig",...]],null,"en"] */
    private fun parseGoogleResult(json: String): String {
        val sb = StringBuilder()
        try {
            // Extract all translated segments
            val parts = Regex("\"([^\"]*)\"").findAll(json).toList()
            if (parts.isEmpty()) return json
            // First quoted string in each sub-array is the translation
            var depth = 0; var inTrans = false
            for (i in parts.indices) {
                // Heuristic: the first quoted string after "[[" is the translation
                if (json.substring(0, parts[i].range.first).count { it == '[' } >= 3 &&
                    json.substring(0, parts[i].range.first).count { it == '[' } < 5) {
                    sb.append(parts[i].groupValues[1])
                }
            }
        } catch (_: Exception) { }
        return sb.toString().ifBlank { json.take(200) }
    }

    companion object {
        /**
         * 从 /Translate 任务文本提取目标语言代码 (ISO 639-1 / zh-CN)。
         * 支持中文指令 ("翻译成英文/日语/法语…") 与英文指令 ("translate to Japanese")。
         * 未识别显式目标语言时返回 [fallback] (调用方按 UI 语言决定默认值)。
         */
        fun targetLanguageFrom(task: String, fallback: String = "en"): String {
            val zh = Regex(
                """(?:翻译|译|翻)(?:成|为|到)?\s*([\u4e00-\u9fa5]{1,6}?)(?:语言)?(?:[\s:：,，。、]|$)"""
            ).find(task)
            zh?.let { m ->
                val name = m.groupValues[1]
                return when {
                    name.contains("英") -> "en"
                    name.contains("日") -> "ja"
                    name.contains("韩") || name.contains("朝鲜") -> "ko"
                    name.contains("法") -> "fr"
                    name.contains("德") -> "de"
                    name.contains("西") || name.contains("班牙") -> "es"
                    name.contains("俄") -> "ru"
                    name.contains("意") -> "it"
                    name.contains("葡") -> "pt"
                    name.contains("阿拉伯") -> "ar"
                    name.contains("泰") -> "th"
                    name.contains("越") -> "vi"
                    name.contains("印尼") -> "id"
                    name.contains("中") || name.contains("汉") -> "zh-CN"
                    else -> name.take(2).lowercase()
                }
            }
            val en = Regex(
                """(?:translate|render|convert)(?:\s+this\s+(?:text|sentence|paragraph|passage))?(?:\s+to)?\s+([a-zA-Z-]+)""",
                setOf(RegexOption.IGNORE_CASE)
            ).find(task)
            en?.let { m ->
                val name = m.groupValues[1].trim().lowercase()
                return when {
                    name.startsWith("japan") -> "ja"
                    name.startsWith("korea") -> "ko"
                    name.startsWith("french") || name == "fr" -> "fr"
                    name.startsWith("german") -> "de"
                    name.startsWith("spanish") -> "es"
                    name.startsWith("russian") -> "ru"
                    name.startsWith("italian") -> "it"
                    name.startsWith("portuguese") -> "pt"
                    name.startsWith("arabic") -> "ar"
                    name.startsWith("thai") -> "th"
                    name.startsWith("vietnamese") -> "vi"
                    name.startsWith("indonesian") -> "id"
                    name.startsWith("chinese") || name.startsWith("mandarin") || name.startsWith("zh") -> "zh-CN"
                    name.startsWith("english") || name == "en" -> "en"
                    else -> name
                }
            }
            return fallback
        }

        /**
         * 剥离 /Translate 任务中的翻译指令前缀, 取待译文本。
         * 例: "翻译成英文: hello" → "hello"; "翻译：你好" → "你好"; 无指令前缀 → 返回整段。
         */
        fun textToTranslate(task: String): String {
            val stripped = task.replaceFirst(
                Regex(
                    """^(?:请\s*)?(?:把|将|给\s*)?(?:这(?:段|句|个)|以下|上面|上述|下面)?\s*(?:文本|内容|文字|话|句子|一段话)?\s*(?:翻译|译|翻)(?:(?:成|为|到)?\s*(?:[\u4e00-\u9fa5]{1,6}?|[\w-]{1,16}?)(?:语言)?)?[\s:：,，。、]+"""
                ),
                ""
            ).trim()
            // 英文指令分支: "translate to English: hello"
            val strippedEn = stripped.replaceFirst(
                Regex(
                    """^(?:please\s+)?(?:translate|render|convert)(?:\s+this\s+(?:text|sentence|paragraph|passage))?(?:\s+to)?\s+[\w-]+\s*:?\s*""",
                    setOf(RegexOption.IGNORE_CASE)
                ),
                ""
            ).trim()
            return strippedEn.ifBlank { task }
        }
    }
}
