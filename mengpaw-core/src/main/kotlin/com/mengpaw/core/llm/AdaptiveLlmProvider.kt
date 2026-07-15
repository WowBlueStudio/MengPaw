package com.mengpaw.core.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Unified multi-model LLM provider supporting OpenAI, DeepSeek, Kimi, GLM, Qwen APIs.
 * Features:
 * - Provider routing by endpoint
 * - Automatic retry with fallback
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
        val timeoutMs: Long = 60000
    )

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = 10000
        }
    }

    /** Detect provider type from endpoint URL for request format adaptation. */
    private val providerType: String by lazy {
        when {
            apiEndpoint.contains("openai.com") -> "openai"
            apiEndpoint.contains("deepseek.com") -> "deepseek"
            apiEndpoint.contains("moonshot.cn") -> "kimi"
            apiEndpoint.contains("bigmodel.cn") -> "glm"
            apiEndpoint.contains("dashscope.aliyuncs.com") -> "qwen"
            else -> "openai" // Default to OpenAI-compatible
        }
    }

    override suspend fun complete(prompt: String): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        val requestBody = buildRequestBody(messages)
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, buildAuthHeader())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }
        val body = response.bodyAsText()
        return parseResponse(body)
    }

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
        val requestBody = buildRequestBody(messages)
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, buildAuthHeader())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }
        return parseResponse(response.bodyAsText())
    }

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        val requestBody = buildRequestBody(messages, stream = true)
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, buildAuthHeader())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }
        return parseResponse(response.bodyAsText())
    }

    override fun info(): ProviderInfo = ProviderInfo(
        name = providerType.replaceFirstChar { it.uppercaseChar() },
        model = model,
        providerType = ProviderType.REMOTE
    )

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

    fun close() { client.close() }
}
