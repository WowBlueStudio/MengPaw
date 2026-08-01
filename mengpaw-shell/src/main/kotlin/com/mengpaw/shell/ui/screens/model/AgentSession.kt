// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens.model

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Per-agent session: independent engine, provider, and message history.
 */
class AgentSession(
    val name: String,
    val framework: String?,
    var modelName: String,
    var endpoint: String = "",
    var apiKey: String = "",
    var provider: LlmProvider,
    val engine: AgentEngine,
    val messages: MutableStateFlow<List<ChatMessageUi>>,
    val scrollContext: ScrollContextManager,
    val isRunning: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val inputEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true)
) {
    val providerLabel: String get() {
        if (endpoint.isBlank() || apiKey.isBlank()) return "智能体还未配置模型"
        val p = when {
            endpoint.contains("openai.com") -> "OpenAI"
            endpoint.contains("deepseek.com") -> "DeepSeek"
            endpoint.contains("x.ai") -> "Grok"
            endpoint.contains("moonshot.cn") -> "Kimi"
            endpoint.contains("bigmodel.cn") -> "GLM"
            endpoint.contains("dashscope") -> "Qwen"
            endpoint.contains("volces.com") -> "火山引擎"
            endpoint.contains("openmodel.ai") -> "OpenModel"
            else -> "Custom"
        }
        val modelLabel = modelName.take(24).ifBlank { "auto" }
        return "$p / $modelLabel"
    }
}
