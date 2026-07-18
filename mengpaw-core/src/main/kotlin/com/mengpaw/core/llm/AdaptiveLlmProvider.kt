package com.mengpaw.core.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * Fallback provider entry for automatic degradation.
 */
data class FallbackEntry(
    val apiEndpoint: String,
    val apiKey: String,
    val model: String = "gpt-4o"
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
    private val model: String = "gpt-4o",
    private val config: AdaptiveConfig = AdaptiveConfig()
) : LlmProvider {

    data class AdaptiveConfig(
        val maxTokens: Int = 4096,
        val temperature: Double = 0.7,
        val timeoutMs: Long = 60_000,
        val maxRetries: Int = 2,
        val retryDelayMs: Long = 500,
        val fallbacks: List<FallbackEntry> = emptyList()
    )

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = 10_000
        }
    }

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

        throw LlmFallbackExhaustedException(
            "All LLM providers exhausted (primary + ${fallbackProviders.size} fallbacks)",
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
                    provider.callDirectApi(messages, stream, onToken)
                } else {
                    provider.completeWithMessages(messages)
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < config.maxRetries) {
                    val delayMs = config.retryDelayMs * (1L shl attempt) // exponential backoff
                    delay(delayMs)
                }
            }
        }

        throw lastError ?: RuntimeException("$label: exhausted retries with no captured error")
    }

    /**
     * Direct API call (bypasses retry/fallback — used internally by executeWithRetry).
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
        val body = response.bodyAsText()

        // Validate HTTP-level error
        if (!response.status.isSuccess()) {
            throw LlmApiException(
                response.status.value,
                "HTTP ${response.status.value}: ${body.take(200)}"
            )
        }

        return parseResponse(body)
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
                        put("content", msg["content"] ?: "")
                    }
                }
            }
        }
        return json.toString()
    }

    private fun parseResponse(body: String): String {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            // Standard OpenAI format
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { choice ->
                choice["message"]?.jsonObject?.let { msg ->
                    return msg["content"]?.jsonPrimitive?.content ?: body
                }
                choice["delta"]?.jsonObject?.let { delta ->
                    return delta["content"]?.jsonPrimitive?.content ?: ""
                }
            }
            // GLM format
            root["data"]?.jsonArray?.firstOrNull()?.jsonObject?.let { data ->
                return data["content"]?.jsonPrimitive?.content ?: body
            }
            body
        } catch (e: Exception) {
            body.take(500)
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
        endpoint.contains("moonshot.cn") -> "kimi"
        endpoint.contains("bigmodel.cn") -> "glm"
        endpoint.contains("dashscope.aliyuncs.com") -> "qwen"
        else -> "openai"
    }

    fun close() {
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
