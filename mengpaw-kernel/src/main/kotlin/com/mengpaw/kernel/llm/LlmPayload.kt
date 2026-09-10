// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JsonNull 安全的对象取值 (v0.46.3 P0) — kotlinx 的 `JsonElement.jsonObject` 在元素是
 * **JsonNull** 时抛 IllegalArgumentException (`?.` 只挡 Kotlin null, 挡不住 JsonNull)。
 *
 * 该缺陷 2026-09-10 造成 DeepSeek 全线"模型未返回任何内容（空响应）":
 * V4.1 Flash 起**每个 SSE 分片都带 `"usage": null`**, 旧实现 `json["usage"]?.jsonObject`
 * 逐片抛异常, 又被 SSE 循环的 `catch (_: Exception)` 连整条事件一起吞掉 →
 * 正文/思维链增量恒为 0, 只有末尾那个带真实 usage 的分片能解析 (所以用量统计反而正常)。
 */
internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

/** JsonNull 安全的数组取值 (同 [objOrNull])。 */
internal fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray

/**
 * 取 OpenAI 兼容正文增量 (v0.46.3 加固) — 官方形态是纯字符串, 但部分网关/多模态型号把
 * `content` 下发为**内容块数组** `[{"type":"text","text":"…"}]`。旧实现直接 `jsonPrimitive`
 * 取值, 遇数组抛 IllegalArgumentException, 又被 SSE 循环的 `catch (_: Exception)` 连**整条事件**
 * 一起丢弃 → 正文永久零增量, 上层只能报「模型未返回任何内容（空响应）」。
 * 现: 数组形态按块拼接 text; 无法识别时返回 null 由调用方记录形态 (不静默丢事件)。
 */
internal fun extractTextDelta(element: JsonElement?): String? = when (element) {
    null, JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotEmpty() }
    is JsonArray -> element.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
    }.joinToString("").takeIf { it.isNotEmpty() }
    else -> null
}

/** 结构描述 — **只含键名与类型, 绝不含内容值**, 用于线上定位异常线格式。 */
internal fun shapeOf(element: JsonElement?): String = when (element) {
    null, JsonNull -> "null"
    is JsonArray -> "array(size=${element.size})"
    is JsonObject -> "object(keys=${element.keys.joinToString(",")})"
    else -> "primitive"
}

/**
 * 从「非流式 JSON 整包」里抢救 message/delta 正文 (v0.46.3) —
 * 部分中转/网关忽略 `stream: true` 直接回整包 JSON, 此时 SSE 逐行解析得到零增量,
 * 会被上层误判为「模型未返回任何内容」。解析失败返回 null (绝不把原始报文当回答)。
 */
internal fun extractMessageContentOrNull(body: String): String? = try {
    val root = Json.parseToJsonElement(body).objOrNull() ?: return null
    val choice = root["choices"].arrOrNull()?.firstOrNull().objOrNull()
    val carrier = choice?.get("message").objOrNull() ?: choice?.get("delta").objOrNull()
    carrier?.get("content")?.let { extractTextDelta(it) }
} catch (_: Exception) {
    null
}

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
    includeReasoning: Boolean = false,
    /**
     * 思考强度档位 (v0.46.2, 仅 DeepSeek 端点 — 调用方经 [effectiveThinkingEffort] 过滤):
     * 官方 思考模式 文档原文 `{"thinking":{"type":"enabled/disabled"}}` +
     * `{"reasoning_effort":"low/high/max"}` 为顶层字段 (OpenAI 格式即请求体顶层)。
     * null = 不注入 (保持既有行为)。
     */
    thinkingEffort: ThinkingEffort? = null
): String {
    // 前缀形状监测 — system prompt 变化即告警
    val firstMsg = messages.firstOrNull()
    if (firstMsg?.get("role") == "system") SystemPromptShape.monitor(firstMsg["content"] ?: "")

    val json = buildJsonObject {
        put("model", model)
        put("max_tokens", config.maxTokens)
        put("temperature", config.temperature)
        put("stream", stream)
        // v0.46.0 P0: 流式必须请求 include_usage — 否则 OpenAI 兼容系(DeepSeek 等)流式响应
        // 默认不带 usage, SseStreamParser.onUsage 永不触发 → lastUsage 恒 null → 用量统计(调用/输入输出Token)恒 0
        if (stream) putJsonObject("stream_options") { put("include_usage", true) }
        // DeepSeek 思考强度 (v0.46.2): 四档 Max/High/Low/Off — 官方 思考模式 文档原文
        // 顶层 `thinking.type` 开关 + `reasoning_effort` 强度; Off 档只发 disabled (不发强度)。
        thinkingEffort?.let { effort ->
            putJsonObject("thinking") { put("type", if (effort.thinkEnabled) "enabled" else "disabled") }
            if (effort.thinkEnabled) put("reasoning_effort", effort.wire)
        }
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
        val root = Json.parseToJsonElement(body).objOrNull() ?: return ParsedLlmBody(fallback, null, null)
        // 1. 提取 usage (v0.46.3: objOrNull — "usage":null 时不得抛异常, 否则整包解析失败回退原文)
        val usage = root["usage"].objOrNull()?.let { u ->
            val pt = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0
            val ct = u["completion_tokens"]?.jsonPrimitive?.int ?: 0
            val tt = u["total_tokens"]?.jsonPrimitive?.int ?: (pt + ct)
            val ch = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.int ?: 0
            val cm = u["prompt_cache_miss_tokens"]?.jsonPrimitive?.int ?: 0
            TokenUsage(pt, ct, tt, ch, cm)
        }
        // 2. 提取 content / reasoning (OpenAI / GLM 等 OpenAI 兼容格式)
        // v0.46.3: content 支持"内容块数组"形态 (extractTextDelta); 全部取值走 objOrNull/arrOrNull
        // — "usage":null / "message":null 之类合法 JSON 空值不得让整包解析抛异常
        val choice = root["choices"].arrOrNull()?.firstOrNull().objOrNull()
        val message = choice?.get("message").objOrNull()
        val delta = choice?.get("delta").objOrNull()
        val rawContent = extractTextDelta(message?.get("content"))
            ?: extractTextDelta(delta?.get("content"))
            ?: extractTextDelta(root["data"].arrOrNull()?.firstOrNull().objOrNull()?.get("content"))
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
