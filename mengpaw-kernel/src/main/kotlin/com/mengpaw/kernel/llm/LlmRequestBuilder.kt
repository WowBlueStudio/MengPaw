// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.KernelLog

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
            calibratedTokPerChar = FALLBACK_TOK_PER_CHAR
        }
    }

    var calibratedTokPerChar: Double = FALLBACK_TOK_PER_CHAR; private set

    fun calibrateFromUsage(promptTokens: Int, totalChars: Int) {
        if (promptTokens > 0 && totalChars > 0) {
            val r = promptTokens.toDouble() / totalChars
            if (r in 0.05..2.0) calibratedTokPerChar = r
        }
    }

    var cacheStrategy: CacheStrategy = CacheStrategy.PREFIX_STABLE

    fun buildMessages(messages: List<Map<String, String>>, injectCacheAnnotations: Boolean = false): List<Map<String, String>> {
        val sys = if (injectCacheAnnotations && cacheStrategy == CacheStrategy.CACHE_CONTROL)
            mapOf("role" to "system", "content" to _systemPrompt, "_cache_control" to "ephemeral")
        else mapOf("role" to "system", "content" to _systemPrompt)
        return listOf(sys) + messages
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
                "data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
            } catch (_: Exception) { imagePath }
            return multimodalMessage(prompt, base64)
        }
    }
}

enum class CacheStrategy {
    PREFIX_STABLE, CACHE_CONTROL, NONE;
    companion object {
        fun forProvider(endpoint: String): CacheStrategy = when {
            "deepseek.com" in endpoint -> PREFIX_STABLE
            "openai.com" in endpoint -> CACHE_CONTROL
            "moonshot.cn" in endpoint || "kimi.com" in endpoint -> CACHE_CONTROL
            "bigmodel.cn" in endpoint -> CACHE_CONTROL
            "dashscope" in endpoint -> CACHE_CONTROL
            "volces.com" in endpoint -> CACHE_CONTROL
            "x.ai" in endpoint -> PREFIX_STABLE
            "openmodel.ai" in endpoint -> PREFIX_STABLE
            else -> PREFIX_STABLE
        }
    }
}

/**
 * 前缀形状监测 (v0.29.2, Reasonix cache_shape.go 对标)。
 *
 * 对每轮请求 wire 上的 system prompt 做 SHA-256 — 形状变化即告警:
 * 自动前缀缓存将短暂失效 (DeepSeek 命中省 ~50 倍成本)。调用点:
 * AdaptiveLlmProvider / RemoteApi 的 buildRequestBody (首条消息 role=system)。
 * 与 PromptEngine 的 mtime 指纹缓存互补: 后者保证组装结果稳定, 这里实测 wire 形状。
 */
internal object SystemPromptShape {
    @Volatile private var lastHash: String? = null
    @Volatile private var lastLen: Int = -1

    fun monitor(systemPrompt: String) {
        val h = sha256Hex(systemPrompt)
        val prev = lastHash
        lastHash = h
        lastLen = systemPrompt.length
        if (prev != null && h != prev) {
            KernelLog.w("CacheShape",
                "cache prefix changed: ${prev.take(8)}…→${h.take(8)}… (len $lastLen) — 自动前缀缓存将短暂失效")
        }
    }

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
