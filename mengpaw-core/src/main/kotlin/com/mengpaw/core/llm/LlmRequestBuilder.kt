// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.llm

import kotlinx.serialization.json.*

/**
 * Builds LLM API requests optimized for cross-provider prompt caching.
 *
 * - DeepSeek: automatic prefix caching via byte-stable prefix
 * - OpenAI/Kimi/GLM/Qwen: cache_control breakpoint injection
 *
 * Cost impact (DeepSeek V4):
 *   Cache miss = $0.14/1K, Cache hit = $0.0028/1K (50x cheaper)
 */
class LlmRequestBuilder(systemPrompt: String) {
    @Volatile private var _systemPrompt: String = systemPrompt
    val currentSystemPrompt: String get() = _systemPrompt

    fun updateSystemPrompt(newPrompt: String) {
        if (newPrompt != _systemPrompt) {
            _systemPrompt = newPrompt
            cumulativeCacheHitTokens = 0; cumulativeCacheMissTokens = 0
            calibratedTokPerChar = FALLBACK_TOK_PER_CHAR  // reset — new model = new tokenizer
        }
    }

    var cumulativeCacheHitTokens: Long = 0; private set
    var cumulativeCacheMissTokens: Long = 0; private set
    var lastPromptTokens: Int = 0; internal set

    var calibratedTokPerChar: Double = FALLBACK_TOK_PER_CHAR; private set

    fun calibrateFromUsage(promptTokens: Int, totalChars: Int) {
        if (promptTokens > 0 && totalChars > 0) {
            val r = promptTokens.toDouble() / totalChars
            if (r in 0.05..2.0) calibratedTokPerChar = r
        }
    }

    /** Current cache strategy for the configured provider. */
    var cacheStrategy: CacheStrategy = CacheStrategy.PREFIX_STABLE

    fun buildMessages(messages: List<Map<String, String>>, injectCacheAnnotations: Boolean = false): List<Map<String, String>> {
        val sys = if (injectCacheAnnotations && cacheStrategy == CacheStrategy.CACHE_CONTROL)
            mapOf("role" to "system", "content" to _systemPrompt, "_cache_control" to "ephemeral")
        else mapOf("role" to "system", "content" to _systemPrompt)
        return listOf(sys) + messages
    }

    fun buildRequest(messages: List<Map<String, String>>, tools: List<Map<String, Any>>? = null,
                     streaming: Boolean = false, model: String, maxTokens: Int = 4096,
                     temperature: Double = 0.7): String {
        val full = buildMessages(messages)
        return buildJsonObject {
            put("model", model); put("max_tokens", maxTokens)
            put("temperature", temperature); put("stream", streaming)
            // FIX A3: Include _cache_control and _image fields that buildMessages() injects
            putJsonArray("messages") { full.forEach { msg ->
                addJsonObject {
                    put("role", msg["role"] ?: "user")
                    put("content", msg["content"] ?: "")
                    // Pass through cache control annotation for prefix-cache optimization
                    msg["_cache_control"]?.let { if (it.isNotBlank()) putJsonObject("cache_control") { put("type", "ephemeral") } }
                    // Pass through image URL for multimodal vision requests
                    msg["_image"]?.let { img ->
                        if (img.isNotBlank()) {
                            putJsonArray("content") {
                                addJsonObject { put("type", "text"); put("text", msg["content"] ?: "") }
                                addJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", img) } }
                            }
                        }
                    }
                }
            }}
            if (tools != null && tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolToJson(it)) } }
        }.toString()
    }

    private fun toolToJson(t: Map<String, Any>): JsonElement = buildJsonObject { t.forEach { (k, v) -> put(k, anyToJson(v)) } }
    private fun anyToJson(v: Any): JsonElement = when (v) {
        is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> buildJsonObject { @Suppress("UNCHECKED_CAST") (v as Map<String, Any>).forEach { (k, v2) -> put(k, anyToJson(v2)) } }
        is List<*> -> buildJsonArray { v.forEach { if (it != null) add(anyToJson(it)) } }
        else -> JsonPrimitive(v.toString())
    }

    companion object {
        const val FALLBACK_TOK_PER_CHAR = 0.25

        fun multimodalMessage(text: String, imageUrl: String? = null): Map<String, String> {
            val msg = mutableMapOf("role" to "user", "content" to text)
            if (imageUrl != null && imageUrl.isNotBlank()) msg["_image"] = imageUrl
            return msg
        }
        fun visionMessage(prompt: String, imagePath: String): Map<String, String> {
            val base64 = try {
                val bytes = java.io.File(imagePath).readBytes()
                "data:image/png;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
            } catch (_: Exception) { imagePath }
            return multimodalMessage(prompt, base64)
        }
    }
}

/** Per-provider cache strategy for automatic prompt caching. */
enum class CacheStrategy {
    PREFIX_STABLE, CACHE_CONTROL, NONE;
    companion object {
        fun forProvider(endpoint: String): CacheStrategy = when {
            // DeepSeek: native auto-prefix-cache (byte-stable prefix → KV-cache hit)
            "deepseek.com" in endpoint -> PREFIX_STABLE
            // OpenAI + providers with cache_control breakpoint support
            "openai.com" in endpoint -> CACHE_CONTROL
            "moonshot.cn" in endpoint -> CACHE_CONTROL
            "bigmodel.cn" in endpoint -> CACHE_CONTROL
            "dashscope" in endpoint -> CACHE_CONTROL
            // Volcano Engine (豆包): supports cache_control annotations
            "volces.com" in endpoint -> CACHE_CONTROL
            // Grok (xAI): OpenAI-compatible but no prompt cache yet, PREFIX_STABLE is safe default
            "x.ai" in endpoint -> PREFIX_STABLE
            // OpenModel: serves DeepSeek → inherits PREFIX_STABLE
            "openmodel.ai" in endpoint -> PREFIX_STABLE
            // Default: PREFIX_STABLE keeps system prompt byte-stable — safe for all providers
            else -> PREFIX_STABLE
        }
        fun labelFor(s: CacheStrategy) = when (s) {
            PREFIX_STABLE -> "自动前缀缓存（DeepSeek 模式）"
            CACHE_CONTROL -> "Prompt Caching（OpenAI 模式）"
            NONE -> "未优化"
        }
    }
}
