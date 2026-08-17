// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 全厂商思维链增量提取 (v0.40.4) — 以各厂商官方 API 文档为唯一准则 (2026-08-17 核对):
 *
 * OpenAI 兼容 `choices[0].delta` / `choices[0].message` 键:
 * - `reasoning_content` — DeepSeek (api-docs.deepseek.com/guides/thinking_mode: 思维链经
 *   reasoning_content 返回, 与 content 同级, 流式与 message 均含)、Kimi (platform.kimi.com/
 *   docs/guide/use-thinking-models: delta.reasoning_content / message.reasoning_content)、
 *   GLM/Z.AI (docs.z.ai migrate-to-glm-new: 流式须处理 delta.reasoning_content 与 delta.content)、
 *   Qwen/DashScope (docs.qwencloud.com: 先 reasoning_content 后 content 两阶段)、
 *   豆包/火山方舟 (docs.volcengine.com/docs/6492/2165111: reasoning_content 为思维链字段)、
 *   xAI (docs.x.ai deferred-chat-completions: message.reasoning_content; grok-4 不返回)。
 * - `reasoning` / `thought` / `thinking` — 官方 OpenAI 兼容文档未记载的兼容兜底键:
 *   Ollama 新版 /v1 实测用 `reasoning` (官方 docs.ollama.com 仅记载原生 /api/chat 的
 *   message.thinking), 部分自建兼容服务用 thought/thinking。仅兜底, 不做官方口径声明。
 * - MiniMax (platform.minimaxi.com/docs/api-reference/text-openai-api, 2026-08-17):
 *   `reasoning_split=true` 时 thinking 经 `reasoning_content` 与 `reasoning_details` 返回;
 *   `reasoning_details` 是数组 (每项含 type/id/format/index/text), 流式 delta 的 text 为
 *   "当前块累计全文" (官方 OpenAI SDK 流式示例按全文 buffer 取增量) — 本类提供
 *   [reasoningDetails] 提取, 流式侧做增量去重; 默认 (不注入 reasoning_split) 时 thinking
 *   内联在 content 的 `<think>...</think>` 标签内, 由 [stripThinkTags]/[ThinkTagSplitter] 剥离。
 *
 * Anthropic 兼容 `content_block_delta`:
 * - `delta.type == "thinking_delta"` → `delta.thinking` (platform.claude.com streaming 文档:
 *   思维块以 thinking_delta 事件流式输出, 块结束前附 signature_delta — 签名不消费不回放)。
 */
internal object ReasoningExtractor {
    /** OpenAI 兼容 delta/message 的思维链键序 — 官方字段在前, 兼容兜底在后。 */
    private val OPENAI_COMPAT_KEYS = listOf("reasoning_content", "reasoning", "thought", "thinking")

    /**
     * 取 OpenAI 兼容对象里的首个非空思维链字符串; 非字符串/空值安全跳过, 绝不抛异常。
     *
     * 决策定案 (2026-08-17 用户拍板): 多键同时出现时**只取第一个** —
     * reasoning_content 与 reasoning 同包出现的情形 ~99% 是网关为兼容不同客户端
     * 重复下发同一段思考, 拼接会导致思考内容显示两遍; 为极小概率的"两段不同思考"
     * 引入去重拼接得不偿失。若未来确有分段场景, 再按"内容不同才拼接"演进。
     */
    fun openAiCompat(delta: JsonObject): String? {
        for (key in OPENAI_COMPAT_KEYS) {
            val text = stringOrNull(delta[key])
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /**
     * MiniMax 官方 `reasoning_details` 提取 (platform.minimaxi.com 工具使用&交错思维链 +
     * OpenAI SDK 文档, 2026-08-17 核对): `reasoning_split=true` 时 thinking 单独输出到该
     * 字段, 数组每项形如 `{type:"reasoning.text", id, format, index, text}`。非流式 message
     * 的 text 为完整思考文本; 流式 delta 的 text 为"当前块累计全文", 由调用方
     * (SseStreamParser) 按官方示例的 buffer 语义取增量, 本函数只返回数组全部 text 拼接。
     *
     * 优先级: 与 `reasoning_content` 同时出现时走本通道 (官方 OpenAI SDK 流式示例只消费
     * reasoning_details), 避免双通道重复显示。
     */
    fun reasoningDetails(delta: JsonObject): String? =
        (delta["reasoning_details"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { item -> stringOrNull((item as? JsonObject)?.get("text")) }
            ?.joinToString("")
            ?.takeIf { it.isNotEmpty() }

    /** 内联思考标签 — MiniMax 默认格式: content 含 `<think>...</think>` (官方文档原文)。 */
    const val THINK_OPEN = "<think>"
    const val THINK_CLOSE = "</think>"

    /**
     * 非流式正文剥离 `<think>...</think>` (MiniMax 默认输出格式, 官方原文:
     * "content 字段会包含 <think> 标签内容"). 返回 (剥离后的正文, 思考文本); 无标签时
     * 原样返回 (thinking = null)。未闭合的 `<think>` 余段归入 thinking, 不污染正文。
     */
    fun stripThinkTags(text: String): Pair<String, String?> {
        if (!text.contains(THINK_OPEN)) return text to null
        val content = StringBuilder()
        val thinking = StringBuilder()
        var i = 0
        var inThink = false
        while (i < text.length) {
            if (!inThink) {
                val open = text.indexOf(THINK_OPEN, i)
                if (open < 0) {
                    content.append(text, i, text.length)
                    break
                }
                content.append(text, i, open)
                inThink = true
                i = open + THINK_OPEN.length
            } else {
                val close = text.indexOf(THINK_CLOSE, i)
                if (close < 0) {
                    thinking.append(text, i, text.length)
                    break
                }
                thinking.append(text, i, close)
                inThink = false
                i = close + THINK_CLOSE.length
            }
        }
        val think = thinking.toString()
        return content.toString() to think.takeIf { it.isNotEmpty() }
    }

    /**
     * 取 Anthropic content_block_delta 的思维链文本: 仅当 `delta.type == "thinking_delta"`
     * 时返回 `delta.thinking`; signature_delta/text_delta 等其它类型返回 null (调用方走正文/忽略)。
     */
    fun anthropicThinkingDelta(delta: JsonObject): String? {
        if (stringOrNull(delta["type"]) != "thinking_delta") return null
        return stringOrNull(delta["thinking"])?.takeIf { it.isNotEmpty() }
    }

    private fun stringOrNull(v: JsonElement?): String? = (v as? JsonPrimitive)?.contentOrNull
}

/**
 * 流式 `<think>...</think>` 剥离器 (MiniMax 默认格式, 2026-08-17 官方文档核对):
 * 思维链增量走 [onReasoning], 正文增量走 [onToken], 标签本身丢弃; 支持标签跨 chunk 拆分
 * (尾部保留期待标签的前缀, 待后续 chunk 补全后再判定)。
 */
internal class ThinkTagSplitter(
    private val onToken: (String) -> Unit,
    private val onReasoning: (String) -> Unit
) {
    private var inThink = false
    private var pending = ""

    fun feed(chunk: String) {
        pending += chunk
        while (true) {
            val tag = if (inThink) ReasoningExtractor.THINK_CLOSE else ReasoningExtractor.THINK_OPEN
            val idx = pending.indexOf(tag)
            if (idx >= 0) {
                val before = pending.substring(0, idx)
                if (before.isNotEmpty()) {
                    if (inThink) onReasoning(before) else onToken(before)
                }
                inThink = !inThink
                pending = pending.substring(idx + tag.length)
                continue
            }
            // 无完整标签: 尾部可能是被拆分的标签前缀 (如 "<th" / "</th"), 保留待补全
            var keep = 0
            for (len in tag.length - 1 downTo 1) {
                if (pending.endsWith(tag.substring(0, len))) {
                    keep = len
                    break
                }
            }
            val consumeLen = pending.length - keep
            if (consumeLen > 0) {
                val consume = pending.substring(0, consumeLen)
                if (inThink) onReasoning(consume) else onToken(consume)
            }
            pending = pending.substring(consumeLen)
            break
        }
    }

    /** 流结束收尾: 未消费余段按当前状态输出 (未闭合 think 视为思考)。 */
    fun finish() {
        if (pending.isNotEmpty()) {
            if (inThink) onReasoning(pending) else onToken(pending)
            pending = ""
        }
        inThink = false
    }
}

/**
 * 流式思维链累积器 (v0.40.4 P2 合并复用): 包装 onReasoning 回调, 同步累积全文,
 * 供 provider 的 lastReasoning 观测字段使用 — AdaptiveLlmProvider / RemoteApi 共用,
 * 消除两份重复的"回调转发 + StringBuilder 累积"代码。
 */
internal class ReasoningAccumulator {
    private val buf = StringBuilder()

    /** 构造透传回调: 先累积, 再转发给上游 (上游可为 null)。 */
    fun callback(upstream: ((String) -> Unit)?): (String) -> Unit = { delta ->
        buf.append(delta)
        upstream?.invoke(delta)
    }

    /** 累积结果 — 空串归一为 null (与"本次调用未返回思考"同语义)。 */
    val text: String? get() = buf.toString().ifEmpty { null }
}
