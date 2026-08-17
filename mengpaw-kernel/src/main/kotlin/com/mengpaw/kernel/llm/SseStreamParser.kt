// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.KernelLog
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
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
 * - DeepSeek thinking mode `reasoning_content` delta — 与 content 分流:
 *   思维链经 [onReasoning] 单独回调 (v0.40.3, 对齐 DeepSeek thinking mode 文档),
 *   绝不混入 [onToken]/fullContent — 否则 UI 流式缓冲被思维链污染, 误判
 *   "Final Answer:"/"Action:" (用户 v0.40.1/0.40.2 复现三症状的根因)
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
        if (line.isEmpty() || !line.startsWith("data:")) continue

        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") break

        try {
            val json = Json.parseToJsonElement(data).jsonObject

            // Capture usage from inline usage event (some APIs include it in last chunk)
            json["usage"]?.jsonObject?.let { u ->
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
            val openAiDelta = json["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject

            if (openAiDelta != null) {
                // Visible text delta (OpenAI standard)
                openAiDelta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) {
                        if (firstToken) { firstToken = false; KernelLog.d("MengPawLatency", "S-FIRST") }
                        fullContent.append(text)
                        onToken(text)
                    }
                }
                // Reasoning delta (DeepSeek thinking mode reasoning_content) —
                // 思维链走独立回调, 不进 fullContent/onToken (v0.40.3)
                openAiDelta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) onReasoning?.invoke(text)
                }
            } else {
                // Anthropic content_block_delta: delta.text (text_delta)
                val text = json["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    if (firstToken) { firstToken = false; KernelLog.d("MengPawLatency", "S-FIRST") }
                    fullContent.append(text)
                    onToken(text)
                }
                // Anthropic 事件名校验: 只处理 content_block_delta, 跳过 message_start/message_delta/ping
                // (无 delta.text 的事件自然被上面的 null 检查跳过)
            }
        } catch (_: Exception) {
            // Skip malformed SSE lines (same resilience as Reasonix readStream)
        }
    }

    KernelLog.d("MengPawLatency", "S-DONE len=${fullContent.length}")
    return fullContent.toString()
}
