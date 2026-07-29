// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens.model

import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.llm.TokenUsage

/**
 * Placeholder provider shown when no API key is configured.
 * Returns a helpful message instead of fake simulated responses.
 */
class UnconfiguredLlmProvider : LlmProvider {
    override suspend fun complete(prompt: String): String =
        "请先配置 API Key：打开设置 → 框架设置 → 选择服务商 → 粘贴 API Key → 退出设置。"

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
        complete("")

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val msg = complete(prompt)
        msg.forEach { onToken(it.toString()) }
        return msg
    }

    override fun info(): ProviderInfo = ProviderInfo("未配置", "none", ProviderType.LOCAL)
    override fun close() {}
    override var lastUsage: TokenUsage? = null
}
