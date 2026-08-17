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
 *
 * Anthropic 兼容 `content_block_delta`:
 * - `delta.type == "thinking_delta"` → `delta.thinking` (platform.claude.com streaming 文档:
 *   思维块以 thinking_delta 事件流式输出, 块结束前附 signature_delta — 签名不消费不回放)。
 */
internal object ReasoningExtractor {
    /** OpenAI 兼容 delta/message 的思维链键序 — 官方字段在前, 兼容兜底在后。 */
    private val OPENAI_COMPAT_KEYS = listOf("reasoning_content", "reasoning", "thought", "thinking")

    /** 取 OpenAI 兼容对象里的首个非空思维链字符串; 非字符串/空值安全跳过, 绝不抛异常。 */
    fun openAiCompat(delta: JsonObject): String? {
        for (key in OPENAI_COMPAT_KEYS) {
            val text = stringOrNull(delta[key])
            if (!text.isNullOrEmpty()) return text
        }
        return null
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
