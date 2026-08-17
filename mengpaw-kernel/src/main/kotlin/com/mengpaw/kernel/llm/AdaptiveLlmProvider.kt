// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.KernelLog
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Unified multi-model LLM provider supporting OpenAI, DeepSeek, Kimi, GLM, Qwen APIs.
 * Features:
 * - Provider routing by endpoint
 * - Automatic retry with exponential backoff
 * - Fallback chain: primary → fallback[0] → fallback[1] → ...
 * - Response format normalization
 *
 * v0.32.x (400 行文件拆分批次 1): 请求/响应格式拆至 [LlmPayload.kt] (buildRequestBody/
 * parseBody/buildAuthHeader + TokenUsage/FallbackEntry), SSE 解析拆至 [SseStreamParser.kt]。
 */
class AdaptiveLlmProvider(
    private val apiEndpoint: String,
    private val apiKey: String,
    private val model: String = "gpt-4.1",
    private val config: AdaptiveConfig = AdaptiveConfig(),
    /** v0.29.2: 网络状况门卫 (shell 注入) — 断网快返 + 弱网放慢退避; null = 不启用 */
    private val networkGate: NetworkConditionGate? = null
) : LlmProvider {

    companion object {
        /** HTTP status codes that should NOT be retried (permanent failures). Ref: QwenPaw retry_chat_model.py */
        private val NON_RETRYABLE_STATUSES = setOf(400, 401, 403)
    }

    data class AdaptiveConfig(
        val maxTokens: Int = 4096,
        val temperature: Double = 0.7,
        val maxRetries: Int = 5,         // 6 total attempts (0..5)
        val retryDelayMs: Long = 500,
        val fallbacks: List<FallbackEntry> = emptyList()
    )

    // v0.29.2: 共享客户端 (LlmHttpClient) — 连接池/超时/keep-alive 集中配置,
    // 会话/角色切换不再重建连接池重新握手 (Reasonix 对照 #2)
    private val client = LlmHttpClient.ktor

    /** Token usage from the most recent API call. Read by shell layer for stats collection. */
    @Volatile override var lastUsage: TokenUsage? = null

    /** 最近一次调用的思维链全文 (v0.40.4, 供观测; UI 显示由 onReasoning 流式通道负责)。 */
    @Volatile override var lastReasoning: String? = null

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
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)?
    ): String {
        return callWithRetryAndFallback(messages, stream = true, onToken = onToken, onReasoning = onReasoning)
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
        onToken: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null
    ): String {
        val chain = listOf("primary" to this) + fallbackProviders.mapIndexed { i, fb ->
            "fallback[$i]" to fb
        }

        var lastError: Exception? = null

        for ((label, provider) in chain) {
            try {
                val result = executeWithRetry(provider, label, messages, stream, onToken, onReasoning)
                // v0.29.2: fallback 服务成功后 usage 直通主 provider — 否则壳层读
                // session.provider.lastUsage 恒 null, fallback 调用无缓存统计
                if (provider !== this) {
                    this.lastUsage = provider.lastUsage
                    // v0.40.4 P2-8: 思维链同样直通 — 观测语义与 usage 一致
                    this.lastReasoning = provider.lastReasoning
                }
                return result
            } catch (e: Exception) {
                if (e is CancellationException) throw e  // 取消契约: 用户 stop() 不得被包装成"已重试 6 次"假错误
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
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): String {
        var lastError: Exception? = null

        for (attempt in 0..config.maxRetries) {
            try {
                return if (provider is AdaptiveLlmProvider) {
                    LlmRateLimiter.withLimit {
                        provider.callDirectApi(messages, stream, onToken, onReasoning)
                    }
                } else if (stream && onToken != null) {
                    // v0.28.4: fallback provider 流式化 — 此前恒走非流式 completeWithMessages,
                    // onToken 被丢弃 → 主 API 失败后回答整段弹出 (金字塔彻查根因)
                    provider.completeStreamingWithMessages(messages, onToken, onReasoning)
                } else {
                    provider.completeWithMessages(messages)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e  // 取消契约: 用户 stop() 不进入重试退避
                // Permanent errors — fail immediately, don't retry (ref: QwenPaw retry_chat_model.py)
                if (e is LlmApiException && e.httpStatus in NON_RETRYABLE_STATUSES) throw e
                // Report 429 for coordinated pause across all concurrent callers
                if (e is LlmApiException && e.httpStatus == 429) LlmRateLimiter.report429()
                // v0.29.2 (用户提议): 断网即失败快返 — 重试必败 (每次尝试白烧一次请求+电量),
                // 错误信息直通 LlmFallbackExhaustedException 呈现给用户, 网络恢复后重发
                if (networkGate != null && !networkGate.isOnline()) {
                    throw LlmApiException(0, "网络连接不可用，已中止本次请求。请恢复网络后重试。")
                }
                lastError = e
                if (attempt < config.maxRetries) {
                    // 弱网放慢退避: 质量差 ×3, 中 ×1.5 — 高铁等弱网场景不烧配额 (v0.29.2)
                    val scale = when (networkGate?.quality() ?: 2) {
                        0 -> 3.0
                        1 -> 1.5
                        else -> 1.0
                    }
                    val baseDelay = (config.retryDelayMs * (1L shl attempt)).coerceAtMost(30_000L)
                    val jitteredDelay = (LlmRateLimiter.jitter(baseDelay) * scale).toLong()
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
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)? = null
    ): String {
        val requestStart = System.currentTimeMillis()  // P2-12(自检报告): LLM 耗时统计锚点
        // v0.40.4 P2-8: 调用开始即清空 — 失败/重试不留上次成功调用的陈旧思维链
        lastReasoning = null
        val requestBody = buildRequestBody(model, config, messages, stream)
        KernelLog.d("MengPawLatency", "S-OPEN ${apiEndpoint.take(48)}")
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, buildAuthHeader(providerType, apiKey))
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
            val accumulator = ReasoningAccumulator()
            val result = consumeSseStream(
                response, onToken, requestStart,
                onUsage = { usage ->
                    lastUsage = usage
                    // P2-12(自检报告): token/耗时统计 — 部分 API 仅在末块内联 usage
                    com.mengpaw.kernel.Telemetry.recordLlm(
                        usage.promptTokens, usage.completionTokens,
                        System.currentTimeMillis() - requestStart
                    )
                },
                onReasoning = accumulator.callback(onReasoning)
            )
            lastReasoning = accumulator.text
            return result
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

        // 合并双次 JSON 解析: 一次 parseToJsonElement 同时提取 content / reasoning / usage (v0.40.4)
        val parsed = parseBody(body)
        lastUsage = parsed.usage
        lastReasoning = parsed.reasoning
        // P2-12(自检报告): token/耗时统计 — 非流式响应 usage 直录 (API 无 usage 时记 0)
        com.mengpaw.kernel.Telemetry.recordLlm(
            parsed.usage?.promptTokens ?: 0, parsed.usage?.completionTokens ?: 0,
            System.currentTimeMillis() - requestStart
        )
        return parsed.content
    }

    // ── Fallback Provider Factory ─────────────────────────────────────────

    private fun createFallbackProvider(entry: FallbackEntry): LlmProvider {
        return RemoteApi(
            apiEndpoint = entry.apiEndpoint,
            apiKey = entry.apiKey,
            model = entry.model,
            config = RemoteApi.RemoteConfig(
                maxTokens = config.maxTokens,
                temperature = config.temperature
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
        // v0.29.2: 共享客户端 (LlmHttpClient) 进程级生命周期 — 不关闭
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
