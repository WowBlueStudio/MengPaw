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
 * 非流式 LLM 响应体解析结果 (v0.40.4): content 与 reasoning 分离 — 思维链绝不混入正文。
 */
data class ParsedLlmBody(
    val content: String,
    val reasoning: String?,
    val usage: TokenUsage?
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
    stream: Boolean = false,
    /**
     * DeepSeek 思考模式回传 (v0.41.1 未发布): 官方要求多轮工具调用时 assistant 的
     * reasoning_content 必须原样回传, 否则 API 400 ("The reasoning_content in the
     * thinking mode must be passed back to the API")。仅 DeepSeek 端点启用 —
     * OpenAI 等其它兼容端点不接受该字段, 传了会 400。
     */
    includeReasoning: Boolean = false
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
                    // DeepSeek 思考模式: assistant 思维链原样回传 (仅 deepseek 端点启用)。
                    // 无工具调用的轮次 DeepSeek 官方明确会忽略该字段, 不报错。
                    if (includeReasoning && msg["role"] == "assistant") {
                        msg["reasoning_content"]?.takeIf { it.isNotBlank() }?.let {
                            put("reasoning_content", it)
                        }
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
 * 合并解析 LLM 响应体: 一次 Json.parseToJsonElement 同时提取 content / reasoning / usage
 * (v0.40.4 P2: RemoteApi.parseResponse 也复用本函数, 消除重复提取逻辑)。
 * 取代之前两次独立解析 (parseUsage + parseResponse), 减少 GC 压力.
 * 思维链 (message.reasoning_content, 各厂商官方文档口径) 提取为独立 [ParsedLlmBody.reasoning],
 * 绝不拼进 content — 防止思维链里的 "Final Answer:"/"Action:" 污染正文与 ReAct 判定。
 * @param maxFallbackLength 解析失败/无正文时回退原始文本的最大截断长度 (null = 不截断)
 * @return [ParsedLlmBody] — content 绝不会为 null, reasoning/usage 可能为 null
 */
internal fun parseBody(body: String, maxFallbackLength: Int? = null): ParsedLlmBody {
    val fallback = maxFallbackLength?.let { body.take(it) } ?: body
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
        // 2. 提取 content / reasoning (OpenAI / GLM 等 OpenAI 兼容格式)
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val delta = choice?.get("delta")?.jsonObject
        val rawContent = message?.get("content")?.jsonPrimitive?.content
            ?: delta?.get("content")?.jsonPrimitive?.content
            ?: root["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content
        // MiniMax 默认格式: thinking 内联在 content 的 <think>...</think> 标签内 (官方原文:
        // "content 字段会包含 <think> 标签内容") — 响应侧剥离到 reasoning, 绝不混入正文
        val (content, inlineThink) = rawContent?.let(ReasoningExtractor::stripThinkTags) ?: (null to null)
        // 思维链: 官方独立字段优先 (reasoning_content — DeepSeek/Kimi/GLM/Qwen/豆包/xAI;
        // reasoning_details — MiniMax reasoning_split=true), <think> 内联兜底 (MiniMax 默认)。
        // 双通道同现视为重复, 只取独立字段 (用户定案: 同包多键只取首个)
        val reasoning = message?.let { m ->
            ReasoningExtractor.reasoningDetails(m) ?: ReasoningExtractor.openAiCompat(m)
        } ?: delta?.let { d ->
            ReasoningExtractor.reasoningDetails(d) ?: ReasoningExtractor.openAiCompat(d)
        } ?: inlineThink
        ParsedLlmBody(content ?: fallback, reasoning, usage)
    } catch (_: Exception) {
        ParsedLlmBody(fallback, null, null)
    }
}
