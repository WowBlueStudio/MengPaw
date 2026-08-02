// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 共享测试 LLM Provider（原 Mission/Swarm 测试各自的私有副本 — 提取统一）。
 * 均为零真实网络：按序回放 + 记录每次 prompt + 并发峰值统计。
 */

/** 按序返回响应（超出循环用最后一个），记录每次 prompt，统计并发峰值。 */
class ScriptedLlmProvider(
    private val responses: List<String>,
    val tag: String = "mock"
) : LlmProvider {
    val calls = CopyOnWriteArrayList<String>()
    private val idx = AtomicInteger(0)
    private val active = AtomicInteger(0)

    /** 并发调用峰值 — WIP 闸/并行上限断言用。 */
    @Volatile
    var maxConcurrent: Int = 0
        private set

    override suspend fun complete(prompt: String): String {
        calls.add(prompt)
        val cur = active.incrementAndGet()
        if (cur > maxConcurrent) maxConcurrent = cur
        try {
            return responses[idx.getAndIncrement().coerceAtMost(responses.lastIndex)]
        } finally {
            active.decrementAndGet()
        }
    }

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
        complete(messages.joinToString("\n") { "${it["role"]}:${it["content"]}" })

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
        complete(prompt).also { onToken(it) }

    override fun info(): ProviderInfo = ProviderInfo("mock", tag, ProviderType.LOCAL)
    override fun close() {}
}

/** 每次调用 delay N ms — 并行时序断言用。 */
class DelayLlmProvider(
    private val delayMs: Long = 100,
    private val responses: List<String>
) : LlmProvider {
    val calls = CopyOnWriteArrayList<String>()
    private val idx = AtomicInteger(0)

    override suspend fun complete(prompt: String): String {
        calls.add(prompt)
        delay(delayMs)
        return responses[idx.getAndIncrement().coerceAtMost(responses.lastIndex)]
    }

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
        complete(messages.joinToString("\n") { "${it["role"]}:${it["content"]}" })

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
        complete(prompt).also { onToken(it) }

    override fun info(): ProviderInfo = ProviderInfo("mock", "delay", ProviderType.LOCAL)
    override fun close() {}
}
