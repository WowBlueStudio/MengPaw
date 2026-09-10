// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.KernelLog
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE 流式解析（自 AdaptiveLlmProvider 拆出 — 400 行文件拆分批次 1）。
 *
 * Architecture (matching Reasonix ② SSE 解析层):
 *   bufio.Scanner(resp.Body) → data: line → json.Unmarshal → onToken(delta.content)
 *
 * Handles:
 * - OpenAI-compatible `data: {...}` events with `choices[0].delta.content`
 * - 全厂商思维链增量分流 (v0.40.4, 字段以各厂商官方 API 文档为唯一准则, 见 [ReasoningExtractor]):
 *   思维链经 [onReasoning] 单独回调, 绝不混入 [onToken]/fullContent — 否则 UI 流式缓冲被
 *   思维链污染, 误判 "Final Answer:"/"Action:" (用户 v0.40.1/0.40.2 复现三症状的根因)
 * - Anthropic 兼容 `content_block_delta`: thinking_delta 走 [onReasoning], text_delta 走 [onToken]
 * - `[DONE]` terminator
 * - Inline `usage` in the final event (经 [onUsage] 回调, 调用方写入 lastUsage + 遥测)
 *
 * @param requestStart 请求起始毫秒时间戳 (P2-12 遥测耗时锚点 — 调用方传入)
 * @return 完整拼接的可见内容文本
 */
internal suspend fun consumeSseStream(
    response: HttpResponse,
    onToken: (String) -> Unit,
    requestStart: Long,
    onUsage: (TokenUsage) -> Unit,
    onReasoning: ((String) -> Unit)? = null
): String {
    val channel = response.bodyAsChannel()
    val fullContent = StringBuilder()
    var firstToken = true
    // v0.46.3 诊断: 原始报文采样 (有界) + 事件计数 — 零正文时用于区分「仅思维链」「完全空流」
    // 「网关回整包 JSON」「流内错误」四种成因 (只记计数/形状, 不记任何内容值)
    val rawSample = StringBuilder()
    val rawCap = 16_000
    var dataEvents = 0
    var reasoningChunks = 0
    var anomalousShapes = 0
    var malformedEvents = 0
    // v0.41.0: MiniMax 默认格式 thinking 内联在 content 的 <think>...</think> 标签内 —
    // 剥离后思维链走 onReasoning, 正文走 onToken, 标签本身丢弃
    val thinkSplitter = ThinkTagSplitter(
        onToken = { text ->
            if (firstToken) { firstToken = false; KernelLog.d("MengPawLatency", "S-FIRST") }
            fullContent.append(text)
            onToken(text)
        },
        onReasoning = { text -> reasoningChunks++; onReasoning?.invoke(text) }
    )
    // MiniMax reasoning_details 流式语义 (官方 OpenAI SDK 示例): text 为当前块累计全文,
    // 记录上一次全文, 每次取新增部分 — 否则增量会被重复推送
    var minimaxDetailsFull: String? = null

    while (!channel.isClosedForRead) {
        val line = try {
            channel.readUTF8Line()?.trim()
        } catch (e: CancellationException) {
            throw e   // 取消契约: 绝不吞 CancellationException — 否则用户 stop() 会被包装成重试
        } catch (e: Exception) {
            // v0.28.4: 异常中断不再静默 break — 首 token 前超时(推理思考期)抛 LlmApiException
            // 触发 executeWithRetry 重试 + fallback 链; 已有内容则返回部分(重试会导致 onToken 重复推送)
            if (fullContent.isEmpty()) {
                throw LlmApiException(response.status.value,
                    "Stream interrupted before first token: ${e.message}")
            }
            break
        }

        if (line == null) break
        if (rawSample.length < rawCap) rawSample.append(line).append('\n')
        if (line.isEmpty() || !line.startsWith("data:")) continue

        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") break

        try {
            val json = Json.parseToJsonElement(data).objOrNull() ?: continue

            // ── 流内错误 (v0.46.3 修复) ──
            // 上游用 HTTP 200 + `data: {"error":{...}}` 报错时, 旧实现把该事件当"无 content 的行"
            // 静默跳过, 上层只见零增量 → 一律误报「模型未返回任何内容（空响应）」, 真实原因被完全掩盖。
            // 现解析出原始报错文案并上抛: 限流/过载保留可重试语义, 其余按 400 直通用户可见。
            json["error"].objOrNull()?.let { err ->
                val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString().take(200)
                val status = streamErrorStatus(msg)
                KernelLog.w("SseStreamParser", "流内错误 (status=$status): ${msg.take(160)}")
                throw LlmApiException(status, "上游流内错误: ${msg.take(300)}")
            }

            dataEvents++

            // ⚠ v0.46.3 P0 修复: 必须用 objOrNull (JsonNull 安全) ——
            // DeepSeek V4.1 Flash 起每个分片都带 `"usage": null`, 旧写法 `json["usage"]?.jsonObject`
            // 遇 JsonNull 抛 IllegalArgumentException, 又被下方 catch 连整条事件吞掉 →
            // 所有正文/思维链增量归零, 上层报「模型未返回任何内容（空响应）」。
            // Capture usage from inline usage event (some APIs include it in last chunk)
            json["usage"].objOrNull()?.let { u ->
                onUsage(TokenUsage(
                    promptTokens = u["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                    completionTokens = u["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                    totalTokens = u["total_tokens"]?.jsonPrimitive?.int ?: 0,
                    cacheHitTokens = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.int ?: 0,
                    cacheMissTokens = u["prompt_cache_miss_tokens"]?.jsonPrimitive?.int ?: 0
                ))
            }

            // ── 双格式 delta 提取 ──
            // OpenAI 兼容: {choices:[{delta:{content|reasoning_content}}]}
            // Anthropic 兼容: {type:"content_block_delta", delta:{type:"text_delta", text}}
            //   (api.deepseek.com/anthropic 等 Anthropic Messages SSE 格式)
            val openAiDelta = json["choices"].arrOrNull()
                ?.firstOrNull().objOrNull()
                ?.get("delta").objOrNull()

            if (openAiDelta != null) {
                // Visible text delta (OpenAI standard) — 先经 <think> 剥离器分流
                // v0.46.3: 经 extractTextDelta 取值 (兼容"内容块数组"形态); 形态无法识别时
                // 只记形状并继续处理同事件的 reasoning — 不再因 content 解析异常丢弃整条事件
                val contentElement = openAiDelta["content"]
                val textDelta = extractTextDelta(contentElement)
                if (textDelta != null) {
                    thinkSplitter.feed(textDelta)
                } else if (contentElement != null && contentElement !is JsonNull && anomalousShapes < 3) {
                    anomalousShapes++
                    KernelLog.w("SseStreamParser",
                        "content 形态无法解析: ${shapeOf(contentElement)} deltaKeys=${openAiDelta.keys}")
                }

                // Reasoning delta (v0.40.4 全厂商键归一: reasoning_content/reasoning/thought/thinking) —
                // 思维链走独立回调, 不进 fullContent/onToken。MiniMax reasoning_details 优先
                // (官方 OpenAI SDK 流式示例只消费该字段; text 为累计全文需取增量)
                val details = ReasoningExtractor.reasoningDetails(openAiDelta)
                if (details != null) {
                    val incremental = minimaxDetailsFull
                        ?.takeIf { details.startsWith(it) }
                        ?.let { details.substring(it.length) }
                        ?: details
                    minimaxDetailsFull = details
                    if (incremental.isNotEmpty()) { reasoningChunks++; onReasoning?.invoke(incremental) }
                } else {
                    ReasoningExtractor.openAiCompat(openAiDelta)?.let { text ->
                        if (text.isNotEmpty()) { reasoningChunks++; onReasoning?.invoke(text) }
                    }
                }
            } else {
                // Anthropic content_block_delta:
                // - delta.type == "thinking_delta" → delta.thinking (思维链, v0.40.4)
                // - delta.type == "text_delta" → delta.text (正文)
                // - signature_delta / message_start / message_delta / ping 等自然跳过 (签名不回放)
                val deltaObj = json["delta"].objOrNull()
                if (deltaObj != null) {
                    val thinking = ReasoningExtractor.anthropicThinkingDelta(deltaObj)
                    val text = thinking
                        ?: deltaObj["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        if (thinking != null) {
                            reasoningChunks++
                            onReasoning?.invoke(thinking)
                        } else {
                            if (firstToken) { firstToken = false; KernelLog.d("MengPawLatency", "S-FIRST") }
                            fullContent.append(text)
                            onToken(text)
                        }
                    }
                }
            }
        } catch (e: LlmApiException) {
            throw e   // 流内错误必须上抛 — 不得落进下面的"跳过畸形行"分支
        } catch (e: Exception) {
            // 畸形行容错 (同 Reasonix readStream): 保留跳过语义, 但**必须留痕** —
            // v0.46.3 的 P0 正是"事件内异常被静默吞掉"导致正文全丢却毫无线索 (每次流最多记 3 条)
            if (malformedEvents < 3) {
                malformedEvents++
                KernelLog.w("SseStreamParser",
                    "跳过畸形事件 #$malformedEvents: ${e::class.simpleName}: ${e.message?.take(140)}")
            }
        }
    }

    thinkSplitter.finish()

    // ── 非流式整包兜底 (v0.46.3) ──
    // 部分中转/网关忽略 stream=true 直接回整包 JSON (无 data: 行) → 逐行解析得到零增量,
    // 上层误报「模型未返回任何内容」。此处用原始报文采样抢救一次正文 (解析失败绝不回退原文)。
    if (fullContent.isEmpty()) {
        val salvaged = extractMessageContentOrNull(rawSample.toString())
        if (!salvaged.isNullOrBlank()) {
            KernelLog.w("SseStreamParser", "非流式整包兜底命中: ${salvaged.length} 字符 (网关未按 stream=true 流式返回)")
            fullContent.append(salvaged)
            onToken(salvaged)
        }
    }

    KernelLog.d("MengPawLatency", "S-DONE len=${fullContent.length}")
    if (fullContent.isEmpty()) {
        // 零正文成因线索 (只记计数/形状, 不记内容) — 决定下一步排查方向
        KernelLog.w("SseStreamParser",
            "零正文流: dataEvents=$dataEvents reasoningChunks=$reasoningChunks " +
            "rawLen=${rawSample.length} jsonLike=${rawSample.trimStart().startsWith("{")} " +
            "anomalousContent=$anomalousShapes")
    }
    return fullContent.toString()
}

/**
 * 流内错误的状态码映射 (v0.46.3): 保留限流/过载的可重试语义, 其余按 400 —
 * 400/401/403 在 [AdaptiveLlmProvider] 的 NON_RETRYABLE_STATUSES 中, 直通用户, 不再白重试 6 次。
 */
internal fun streamErrorStatus(message: String): Int {
    val m = message.lowercase()
    return when {
        "rate limit" in m || "429" in m || "too many requests" in m -> 429
        "overload" in m || "busy" in m || "503" in m || "500" in m || "server error" in m -> 503
        else -> 400
    }
}
