// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
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
        val temperature: Double = 0.7,
        val timeoutMs: Long = 120_000
    )

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = 20_000
        }
    }

    override suspend fun complete(prompt: String): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        val requestBody = buildRequestBody(messages)
        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
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
        return parseResponse(response.bodyAsText())
    }

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        val requestBody = buildRequestBody(messages, stream = true)

        val response = client.post(apiEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(requestBody)
        }

        // HTTP error check
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "unknown" }
            return errorBody.take(500)
        }

        // SSE streaming: read data: lines incrementally
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
                val delta = json["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject
                    ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) {
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
            val choices = json["choices"]?.jsonArray ?: return body
            val first = choices.firstOrNull()?.jsonObject ?: return body
            val message = first["message"]?.jsonObject ?: return body
            message["content"]?.jsonPrimitive?.content ?: body
        } catch (e: Exception) {
            body.take(500)
        }
    }

    override fun close() { client.close() }
}
