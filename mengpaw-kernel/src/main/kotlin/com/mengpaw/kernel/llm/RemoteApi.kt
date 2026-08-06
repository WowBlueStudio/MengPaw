// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Remote LLM provider that calls external API endpoints.
 * Supports OpenAI-compatible APIs. Uses manual JSON building (no ContentNegotiation dep).
 */
class RemoteApi(
    private val apiEndpoint: String,
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val config: RemoteConfig = RemoteConfig()
) : LlmProvider {

    data class RemoteConfig(
        val maxTokens: Int = 4096,
        val temperature: Double = 0.7
    )

    // v0.29.2: 共享客户端 (LlmHttpClient) — 与主 provider 同一连接池 (Reasonix 对照 #2)
    private val client = LlmHttpClient.ktor

    /** Token usage from the most recent API call (v0.29.2: fallback 链路缓存统计直通). */
    @Volatile override var lastUsage: TokenUsage? = null

    override suspend fun complete(prompt: String): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        val requestBody = buildRequestBody(messages)
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }
        // HTTP error check — 401/500 等错误体 JSON 不得直接当回答进对话 (与下方流式路径一致)
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "unknown" }
            throw LlmApiException(response.status.value, "HTTP ${response.status.value}: ${errorBody.take(200)}")
        }
        return parseResponse(response.bodyAsText())
    }

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
        val requestBody = buildRequestBody(messages)
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }
        // HTTP error check — 同上: 错误体不得冒充成功回答
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "unknown" }
            throw LlmApiException(response.status.value, "HTTP ${response.status.value}: ${errorBody.take(200)}")
        }
        return parseResponse(response.bodyAsText())
    }

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        return completeStreamingWithMessages(messages, onToken)
    }

    /**
     * 流式 messages 调用 — v0.28.4: 从 completeStreaming 平移, 修正两个缺陷:
     * 1. 原实现是死代码 — fallback 链(AdaptiveLlmProvider.executeWithRetry else 分支)
     *    恒走非流式 completeWithMessages, 此方法从不被调用
     * 2. HTTP 错误原返回 errorBody 文本(会被当作成功响应), 改为抛 LlmApiException
     *    (与 AdaptiveLlmProvider 对齐, 触发上层重试/fallback 链)
     */
    override suspend fun completeStreamingWithMessages(
        messages: List<Map<String, String>>,
        onToken: (String) -> Unit
    ): String {
        val requestBody = buildRequestBody(messages, stream = true)

        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }

        // HTTP error check
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "unknown" }
            throw LlmApiException(response.status.value, "HTTP ${response.status.value}: ${errorBody.take(200)}")
        }

        // SSE streaming: read data: lines incrementally
        val channel = response.bodyAsChannel()
        val fullContent = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = try {
                channel.readUTF8Line()?.trim()
            } catch (e: CancellationException) {
                throw e  // 取消契约: 用户 stop() 不得把半截响应当完整回答返回
            } catch (_: Exception) { break }

            if (line == null) break
            if (line.isEmpty() || !line.startsWith("data:")) continue

            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break

            try {
                val json = Json.parseToJsonElement(data).jsonObject

                // Capture usage from inline usage event (v0.29.2: 与主 provider 对齐)
                json["usage"]?.jsonObject?.let { u ->
                    lastUsage = TokenUsage(
                        promptTokens = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                        completionTokens = u["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                        totalTokens = u["total_tokens"]?.jsonPrimitive?.int ?: 0,
                        cacheHitTokens = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.int ?: 0,
                        cacheMissTokens = u["prompt_cache_miss_tokens"]?.jsonPrimitive?.int ?: 0
                    )
                }

                // OpenAI 兼容: choices[0].delta.content
                val openAiDelta = json["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject
                if (openAiDelta != null) {
                    openAiDelta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        if (text.isNotEmpty()) {
                            fullContent.append(text)
                            onToken(text)
                        }
                    }
                } else {
                    // Anthropic 兼容: content_block_delta → delta.text (text_delta)
                    val text = json["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        fullContent.append(text)
                        onToken(text)
                    }
                }
            } catch (_: Exception) { /* skip malformed SSE */ }
        }

        return fullContent.toString()
    }

    override fun info(): ProviderInfo = ProviderInfo(
        name = "RemoteAPI",
        model = model,
        providerType = ProviderType.REMOTE
    )

    private fun buildRequestBody(messages: List<Map<String, String>>, stream: Boolean = false): String {
        // 前缀形状监测 (v0.29.2, Reasonix cache_shape.go 对标) — 与主 provider 同口径
        val firstMsg = messages.firstOrNull()
        if (firstMsg?.get("role") == "system") SystemPromptShape.monitor(firstMsg["content"] ?: "")

        return buildJsonObject {
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
        }.toString()
    }

    private fun parseResponse(body: String): String {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            // Usage 提取 (v0.29.2: 非流式路径缓存统计, 与主 provider parseBody 对齐)
            json["usage"]?.jsonObject?.let { u ->
                lastUsage = TokenUsage(
                    promptTokens = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                    completionTokens = u["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                    totalTokens = u["total_tokens"]?.jsonPrimitive?.int ?: 0,
                    cacheHitTokens = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.int ?: 0,
                    cacheMissTokens = u["prompt_cache_miss_tokens"]?.jsonPrimitive?.int ?: 0
                )
            }
            val choices = json["choices"]?.jsonArray ?: return body
            val first = choices.firstOrNull()?.jsonObject ?: return body
            val message = first["message"]?.jsonObject ?: return body
            message["content"]?.jsonPrimitive?.content ?: body
        } catch (e: Exception) {
            body.take(500)
        }
    }

    override fun close() {
        // v0.29.2: 共享客户端 (LlmHttpClient) 进程级生命周期 — 不关闭
    }
}
