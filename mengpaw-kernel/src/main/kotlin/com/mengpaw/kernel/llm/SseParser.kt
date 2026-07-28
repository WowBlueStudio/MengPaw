// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.llm

import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*

/**
 * Thread-safe holder for usage data extracted during SSE streaming.
 */
internal class SseUsageHolder {
    @Volatile var usage: TokenUsage? = null
}

/**
 * Parse SSE (Server-Sent Events) from a Ktor [ByteReadChannel] into a [Flow] of delta content strings.
 *
 * SSE format (OpenAI-compatible):
 * ```
 * data: {"id":"...","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}
 * data: [DONE]
 * ```
 *
 * Each [delta.content] is emitted as a string token.
 * The final chunk may contain a "usage" object which is captured into [usageHolder] if provided.
 *
 * @param usageHolder optional holder for capturing token usage from the final chunk
 * @return Flow emitting delta content strings as they arrive
 */
internal fun ByteReadChannel.parseSseEvents(usageHolder: SseUsageHolder? = null): Flow<String> = callbackFlow {
    while (!isClosedForRead) {
        val line = readSseLine() ?: break
        if (!line.startsWith("data: ")) continue
        val data = line.removePrefix("data: ").trimEnd('\r')
        if (data == "[DONE]") { close(); return@callbackFlow }
        try {
            val root = Json.parseToJsonElement(data).jsonObject
            // Extract delta content
            val content = root["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
            if (content != null) trySend(content)
            // Extract usage from final chunk
            if (usageHolder != null) {
                val usageObj = root["usage"]?.jsonObject
                if (usageObj != null) {
                    usageHolder.usage = TokenUsage(
                        promptTokens = usageObj["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                        completionTokens = usageObj["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                        totalTokens = usageObj["total_tokens"]?.jsonPrimitive?.int ?: 0,
                        cacheHitTokens = usageObj["prompt_cache_hit_tokens"]?.jsonPrimitive?.int ?: 0,
                        cacheMissTokens = usageObj["prompt_cache_miss_tokens"]?.jsonPrimitive?.int ?: 0
                    )
                }
            }
        } catch (_: Exception) {
            // Malformed JSON line — skip
        }
    }
    close()
}.flowOn(Dispatchers.IO)

/**
 * Read a single line (terminated by \n or \r\n) from the channel.
 * Properly handles UTF-8 multi-byte characters (Chinese, etc.).
 * Returns null when the channel is closed and no data remains.
 */
private suspend fun ByteReadChannel.readSseLine(): String? {
    val bytes = mutableListOf<Byte>()
    while (!isClosedForRead) {
        val byte = readByte()
        if (byte == '\n'.code.toByte()) {
            return bytes.toByteArray().decodeToString()
        }
        if (byte != '\r'.code.toByte()) bytes.add(byte)
    }
    if (bytes.isEmpty()) return null
    return bytes.toByteArray().decodeToString()
}
