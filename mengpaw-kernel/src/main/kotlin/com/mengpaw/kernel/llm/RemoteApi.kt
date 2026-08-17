// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Remote LLM provider that calls external API endpoints.
 * Supports OpenAI-compatible APIs. Uses manual JSON building (no ContentNegotiation dep).
 */
class RemoteApi(
    private val apiEndpoint: String,
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val config: RemoteConfig = RemoteConfig(),
    /** v0.40.4 P2: 可注入测试客户端 (MockEngine); 默认共享进程级客户端。 */
    private val client: io.ktor.client.HttpClient = LlmHttpClient.ktor
) : LlmProvider {

    data class RemoteConfig(
        val maxTokens: Int = 4096,
        val temperature: Double = 0.7
    )

    /** Token usage from the most recent API call (v0.29.2: fallback 链路缓存统计直通). */
    @Volatile override var lastUsage: TokenUsage? = null

    /** 最近一次调用的思维链全文 (v0.40.4, 与主 provider 同口径)。 */
    @Volatile override var lastReasoning: String? = null

    override suspend fun complete(prompt: String): String {
        // v0.40.4 P2-8: 调用开始时清空 — 失败不留上次成功调用的陈旧值;
        // 思维链是 LLM 瞬态输出, 不属进化记录范畴
        lastReasoning = null
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
        // v0.40.4 P2-8: 同上 — 调用开始即清空, 成功路径由 parseResponse 写入
        lastReasoning = null
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
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)?
    ): String {
        // v0.40.4 P2-8: 同上 — 调用开始即清空, 成功收流后由 accumulator 写入
        lastReasoning = null
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

        // v0.40.4: 直接复用主解析器 consumeSseStream — 删除重复 SSE 循环,
        // 保证主链与 fallback 链同口径 (全厂商思维链分流 / usage / 断流语义)。
        val accumulator = ReasoningAccumulator()
        val result = consumeSseStream(
            response, onToken, System.currentTimeMillis(),
            onUsage = { usage -> lastUsage = usage },
            onReasoning = accumulator.callback(onReasoning)
        )
        lastReasoning = accumulator.text
        return result
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
        // v0.40.4 P2: 复用 parseBody 统一提取 content/reasoning/usage, 消除重复;
        // maxFallbackLength=500 保留原"解析失败回退截断 500 字符"语义 (防错误体整段冒充回答)。
        val parsed = parseBody(body, maxFallbackLength = 500)
        lastUsage = parsed.usage
        lastReasoning = parsed.reasoning
        return parsed.content
    }

    override fun close() {
        // v0.29.2: 共享客户端 (LlmHttpClient) 进程级生命周期 — 不关闭
    }
}
