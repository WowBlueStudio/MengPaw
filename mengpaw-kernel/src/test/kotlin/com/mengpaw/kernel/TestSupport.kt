// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType

/**
 * Agent 引擎测试共用桩 — 自 AgentEngineTest 拆分 (2026-08-21, 926 行超 400 行红线)。
 *
 * · `nextResponse`: 单次固定回答 (多数用例形态)
 * · `responseQueue`: 多轮脚本回答 (按序出队, 队空回落 nextResponse)
 */
internal class MockLlmProvider : LlmProvider {
    var nextResponse: String = "Final Answer: Done."

    val responseQueue = java.util.ArrayDeque<String>()

    private fun take(): String =
        if (responseQueue.isEmpty()) nextResponse else responseQueue.removeFirst()

    override suspend fun complete(prompt: String): String = take()

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val r = take()
        r.forEach { onToken(it.toString()) }
        return r
    }

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = take()

    override fun info(): ProviderInfo = ProviderInfo("mock", "mock-v1", ProviderType.LOCAL)
    override fun close() {}
}
