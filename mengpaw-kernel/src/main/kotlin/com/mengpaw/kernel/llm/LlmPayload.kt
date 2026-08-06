// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
 * Fallback provider entry for automatic degradation.
 */
data class FallbackEntry(
    val apiEndpoint: String,
    val apiKey: String,
    val model: String = "gpt-4.1"
)

/**
 * LLM 请求/响应体格式 (自 AdaptiveLlmProvider 拆出 — 400 行文件拆分批次 1)。
 * 与 provider 路由/重试解耦的纯格式函数 — 参数显式传入, 便于单测与复用。
 */

/** Authorization 头: GLM 用裸 API key, 其余 Bearer 前缀。 */
internal fun buildAuthHeader(providerType: String, apiKey: String): String = when (providerType) {
    "glm" -> apiKey  // GLM uses bare API key (no Bearer prefix)
    else -> "Bearer $apiKey"
}

/**
 * 构建 OpenAI 兼容请求体。支持多模态键 (_image/_audio_data) 与 cache_control 注入。
 * 前缀形状监测 (v0.29.2, Reasonix cache_shape.go 对标): system prompt 变化即告警 —
 * 自动前缀缓存将短暂失效 (DeepSeek 命中省 ~50 倍成本)。
 */
internal fun buildRequestBody(
    model: String,
    config: AdaptiveLlmProvider.AdaptiveConfig,
    messages: List<Map<String, String>>,
    stream: Boolean = false
): String {
    // 前缀形状监测 — system prompt 变化即告警
    val firstMsg = messages.firstOrNull()
    if (firstMsg?.get("role") == "system") SystemPromptShape.monitor(firstMsg["content"] ?: "")

    val json = buildJsonObject {
        put("model", model)
        put("max_tokens", config.maxTokens)
        put("temperature", config.temperature)
        put("stream", stream)
        putJsonArray("messages") {
            messages.forEach { msg ->
                addJsonObject {
                    put("role", msg["role"] ?: "user")
                    // Multimodal (v0.33.0+): _image → image_url, _audio_data → input_audio
                    val imageUrl = msg["_image"]?.takeIf { it.isNotBlank() }
                    val audioData = msg["_audio_data"]?.takeIf { it.isNotBlank() }
                    val textContent = msg["content"] ?: ""
                    if (imageUrl != null || audioData != null) {
                        putJsonArray("content") {
                            if (textContent.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", textContent)
                                }
                            }
                            imageUrl?.let {
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", it)
                                    }
                                }
                            }
                            audioData?.let {
                                addJsonObject {
                                    put("type", "input_audio")
                                    putJsonObject("input_audio") {
                                        put("data", it)
                                        put("format", msg["_audio_format"]?.takeIf { f -> f.isNotBlank() } ?: "m4a")
                                    }
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
internal fun parseBody(body: String): Pair<String, TokenUsage?> {
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
