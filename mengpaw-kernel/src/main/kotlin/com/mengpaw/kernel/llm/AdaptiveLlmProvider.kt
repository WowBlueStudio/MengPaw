// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import io.ktor.utils.io.*
import kotlinx.serialization.json.*

/**
 * Fallback provider entry for automatic degradation.
 */
data class FallbackEntry(
    val apiEndpoint: String,
    val apiKey: String,
    val model: String = "gpt-4.1"
)

/**
 * Token usage data extracted from LLM API response.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0
)

/**
 * Unified multi-model LLM provider supporting OpenAI, DeepSeek, Kimi, GLM, Qwen APIs.
 * Features:
 * - Provider routing by endpoint
 * - Automatic retry with exponential backoff
 * - Fallback chain: primary → fallback[0] → fallback[1] → ...
 * - Response format normalization
 */
class AdaptiveLlmProvider(
    private val apiEndpoint: String,
    private val apiKey: String,
    private val model: String = "gpt-4.1",
    private val config: AdaptiveConfig = AdaptiveConfig()
) : LlmProvider {

    companion object {
        /** HTTP status codes that should NOT be retried (permanent failures). Ref: QwenPaw retry_chat_model.py */
        private val NON_RETRYABLE_STATUSES = setOf(400, 401, 403)
    }

    data class AdaptiveConfig(
        val maxTokens: Int = 4096,
        val temperature: Double = 0.7,
        val timeoutMs: Long = 120_000,   // Total request timeout
        val maxRetries: Int = 5,         // 6 total attempts (0..5)
        val retryDelayMs: Long = 500,
        val fallbacks: List<FallbackEntry> = emptyList()
    )

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs   // 120s total
            connectTimeoutMillis = 20_000             // DNS+TCP+TLS
            socketTimeoutMillis = 60_000              // Idle between packets
        }
    }

    /** Token usage from the most recent API call. Read by shell layer for stats collection. */
    @Volatile override var lastUsage: TokenUsage? = null

    /** Detect provider type from endpoint URL for request format adaptation. */
    private val providerType: String by lazy { detectProviderType(apiEndpoint) }

    /** Lazy-initialized fallback provider instances. */
    private val fallbackProviders: List<LlmProvider> by lazy {
        config.fallbacks.map { createFallbackProvider(it) }
    }

    override suspend fun complete(prompt: String): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        return callWithRetryAndFallback(messages, stream = false, onToken = null)
    }

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
        return callWithRetryAndFallback(messages, stream = false, onToken = null)
    }

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        return callWithRetryAndFallback(messages, stream = true, onToken = onToken)
    }

    override suspend fun completeStreamingWithMessages(
        messages: List<Map<String, String>>,
        onToken: (String) -> Unit
    ): String {
        return callWithRetryAndFallback(messages, stream = true, onToken = onToken)
    }

    override fun info(): ProviderInfo = ProviderInfo(
        name = providerType.replaceFirstChar { it.uppercaseChar() },
        model = model,
        providerType = ProviderType.REMOTE
    )

    // ── Retry & Fallback Engine ──────────────────────────────────────────

    /**
     * Execute an API call with retry and fallback chain:
     *   1. Try primary provider up to [maxRetries] times (exponential backoff)
     *   2. On exhaustion, try each fallback provider in order
     *   3. Each fallback also retries up to [maxRetries] times
     *
     * Throws the last exception if all providers are exhausted.
     */
    private suspend fun callWithRetryAndFallback(
        messages: List<Map<String, String>>,
        stream: Boolean,
        onToken: ((String) -> Unit)? = null
    ): String {
        val chain = listOf("primary" to this) + fallbackProviders.mapIndexed { i, fb ->
            "fallback[$i]" to fb
        }

        var lastError: Exception? = null

        for ((label, provider) in chain) {
            try {
                return executeWithRetry(provider, label, messages, stream, onToken)
            } catch (e: Exception) {
                lastError = e
                // Continue to next provider in the chain
            }
        }

        val cause = lastError?.message?.take(120) ?: "未知网络错误"
        val hint = if (fallbackProviders.isEmpty()) {
            "（可配置备用 API 以提高可用性）"
        } else ""
        throw LlmFallbackExhaustedException(
            "LLM 服务调用失败，已重试 ${config.maxRetries + 1} 次。" +
            "错误原因：$cause。" +
            "请检查网络连接和 API Key 配置。$hint",
            lastError
        )
    }

    /**
     * Retry a single provider up to [maxRetries] times with exponential backoff.
     */
    private suspend fun executeWithRetry(
        provider: LlmProvider,
        label: String,
        messages: List<Map<String, String>>,
        stream: Boolean,
        onToken: ((String) -> Unit)?
    ): String {
        var lastError: Exception? = null

        for (attempt in 0..config.maxRetries) {
            try {
                return if (provider is AdaptiveLlmProvider) {
                    LlmRateLimiter.withLimit {
                        provider.callDirectApi(messages, stream, onToken)
                    }
                } else {
                    provider.completeWithMessages(messages)
                }
            } catch (e: Exception) {
                // Permanent errors — fail immediately, don't retry (ref: QwenPaw retry_chat_model.py)
                if (e is LlmApiException && e.httpStatus in NON_RETRYABLE_STATUSES) throw e
                // Report 429 for coordinated pause across all concurrent callers
                if (e is LlmApiException && e.httpStatus == 429) LlmRateLimiter.report429()
                lastError = e
                if (attempt < config.maxRetries) {
                    val baseDelay = (config.retryDelayMs * (1L shl attempt)).coerceAtMost(30_000L)
                    val jitteredDelay = LlmRateLimiter.jitter(baseDelay)
                    delay(jitteredDelay)
                }
            }
        }

        throw lastError ?: RuntimeException("$label: exhausted retries with no captured error")
    }

    /**
     * Direct API call (bypasses retry/fallback — used internally by executeWithRetry).
     *
     * When [stream]=true and [onToken] is provided, uses SSE line-by-line parsing
     * and invokes [onToken] for each content delta (matching Reasonix readStream pattern).
     */
    internal suspend fun callDirectApi(
        messages: List<Map<String, String>>,
        stream: Boolean,
        onToken: ((String) -> Unit)?
    ): String {
        val requestBody = buildRequestBody(messages, stream)
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, buildAuthHeader())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }

        // Validate HTTP-level error before reading body
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "unknown" }
            throw LlmApiException(
                response.status.value,
                "HTTP ${response.status.value}: ${errorBody.take(200)}"
            )
        }

        // ── Streaming path: SSE line-by-line (Reasonix readStream pattern) ──
        if (stream && onToken != null) {
            return consumeSseStream(response, onToken)
        }

        // ── Non-streaming path ──
        val body = try {
            response.bodyAsText()
        } catch (e: Exception) {
            // HTTP 200 but body read failed (likely socket timeout between packets).
            throw LlmApiException(
                response.status.value,
                "Body read failed after HTTP 200: ${e.message}. Consider increasing socketTimeoutMs."
            )
        }

        // 合并双次 JSON 解析: 一次 parseToJsonElement 同时提取 usage 和 content
        val (parsedContent, usage) = parseBody(body)
        lastUsage = usage
        return parsedContent
    }

    // ── SSE Streaming (Reasonix readStream pattern) ─────────────────────────

    /**
     * Consume an SSE streaming response line by line.
     *
     * Architecture (matching Reasonix ② SSE 解析层):
     *   bufio.Scanner(resp.Body) → data: line → json.Unmarshal → onToken(delta.content)
     *
     * Handles:
     * - OpenAI-compatible `data: {...}` events with `choices[0].delta.content`
     * - DeepSeek `reasoning_content` delta
     * - `[DONE]` terminator
     * - Inline `usage` in the final event
     */
    private suspend fun consumeSseStream(
        response: HttpResponse,
        onToken: (String) -> Unit
    ): String {
        val channel = response.bodyAsChannel()
        val fullContent = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = try {
                channel.readUTF8Line()?.trim()
            } catch (_: Exception) { break }

            if (line == null) break
            if (line.isEmpty() || !line.startsWith("data:")) continue

            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break

            try {
                val json = Json.parseToJsonElement(data).jsonObject

                // Capture usage from inline usage event (some APIs include it in last chunk)
                json["usage"]?.jsonObject?.let { u ->
                    lastUsage = TokenUsage(
                        promptTokens = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                        completionTokens = u["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                        totalTokens = u["total_tokens"]?.jsonPrimitive?.int ?: 0,
                        cacheHitTokens = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.int ?: 0,
                        cacheMissTokens = u["prompt_cache_miss_tokens"]?.jsonPrimitive?.int ?: 0
                    )
                }

                // ── 双格式 delta 提取 ──
                // OpenAI 兼容: {choices:[{delta:{content|reasoning_content}}]}
                // Anthropic 兼容: {type:"content_block_delta", delta:{type:"text_delta", text}}
                //   (api.deepseek.com/anthropic 等 Anthropic Messages SSE 格式)
                val openAiDelta = json["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject

                if (openAiDelta != null) {
                    // Visible text delta (OpenAI standard)
                    openAiDelta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        if (text.isNotEmpty()) {
                            fullContent.append(text)
                            onToken(text)
                        }
                    }
                    // Reasoning delta (DeepSeek reasoning_content)
                    openAiDelta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        if (text.isNotEmpty()) onToken(text)
                    }
                } else {
                    // Anthropic content_block_delta: delta.text (text_delta)
                    val text = json["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        fullContent.append(text)
                        onToken(text)
                    }
                    // Anthropic 事件名校验: 只处理 content_block_delta, 跳过 message_start/message_delta/ping
                    // (无 delta.text 的事件自然被上面的 null 检查跳过)
                }
            } catch (_: Exception) {
                // Skip malformed SSE lines (same resilience as Reasonix readStream)
            }
        }

        return fullContent.toString()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildAuthHeader(): String = when (providerType) {
        "glm" -> apiKey  // GLM uses bare API key (no Bearer prefix)
        else -> "Bearer $apiKey"
    }

    private fun buildRequestBody(messages: List<Map<String, String>>, stream: Boolean = false): String {
        val json = buildJsonObject {
            put("model", model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            put("stream", stream)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg["role"] ?: "user")
                        // Multimodal: if _image is present, build content array
                        val imageUrl = msg["_image"]
                        val textContent = msg["content"] ?: ""
                        if (imageUrl != null && imageUrl.isNotBlank()) {
                            putJsonArray("content") {
                                if (textContent.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", textContent)
                                    }
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", imageUrl)
                                    }
                                }
                            }
                        } else {
                            put("content", textContent)
                        }
                        // Inject cache_control annotation for supported providers
                        if (msg["_cache_control"] == "ephemeral") {
                            putJsonObject("cache_control") {
                                put("type", "ephemeral")
                            }
                        }
                    }
                }
            }
        }
        return json.toString()
    }

    /**
     * 合并解析 LLM 响应体: 一次 Json.parseToJsonElement 同时提取 content 和 usage.
     * 取代之前两次独立解析 (parseUsage + parseResponse), 减少 GC 压力.
     * @return Pair(content, usage) — content 绝不会为 null, usage 可能为 null
     */
    private fun parseBody(body: String): Pair<String, TokenUsage?> {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            // 1. 提取 usage
            val usage = root["usage"]?.jsonObject?.let { u ->
                val pt = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0
                val ct = u["completion_tokens"]?.jsonPrimitive?.int ?: 0
                val tt = u["total_tokens"]?.jsonPrimitive?.int ?: (pt + ct)
                val ch = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.int ?: 0
                val cm = u["prompt_cache_miss_tokens"]?.jsonPrimitive?.int ?: 0
                TokenUsage(pt, ct, tt, ch, cm)
            }
            // 2. 提取 content (OpenAI / GLM 格式)
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { c ->
                c["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: c["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            } ?: root["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content
            Pair(content ?: body, usage)
        } catch (_: Exception) {
            Pair(body, null)
        }
    }

    // ── Fallback Provider Factory ─────────────────────────────────────────

    private fun createFallbackProvider(entry: FallbackEntry): LlmProvider {
        return RemoteApi(
            apiEndpoint = entry.apiEndpoint,
            apiKey = entry.apiKey,
            model = entry.model,
            config = RemoteApi.RemoteConfig(
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                timeoutMs = config.timeoutMs
            )
        )
    }

    private fun detectProviderType(endpoint: String): String = when {
        endpoint.contains("openai.com") -> "openai"
        endpoint.contains("deepseek.com") -> "deepseek"
        endpoint.contains("moonshot.cn") || endpoint.contains("kimi.com") -> "kimi"
        endpoint.contains("bigmodel.cn") -> "glm"
        endpoint.contains("dashscope.aliyuncs.com") -> "qwen"
        endpoint.contains("openmodel.ai") -> "openai"
        endpoint.contains("x.ai") || endpoint.contains("api.x.ai") -> "grok"
        endpoint.contains("volces.com") || endpoint.contains("volcengine") -> "volcano"
        else -> "openai"
    }

    override fun close() {
        client.close()
    }
}

/**
 * Thrown when all providers (primary + fallbacks) have been exhausted.
 */
class LlmFallbackExhaustedException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Thrown when an LLM API returns a non-success HTTP status.
 */
class LlmApiException(val httpStatus: Int, message: String) : Exception(message)
