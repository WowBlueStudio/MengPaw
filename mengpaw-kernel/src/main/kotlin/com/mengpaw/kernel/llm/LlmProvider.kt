// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

/**
 * Core interface for LLM providers (local or remote).
 */
interface LlmProvider : AutoCloseable {
    /**
     * Send a prompt and get a completion.
     */
    suspend fun complete(prompt: String): String

    /**
     * Stream a completion token by token.
     */
    suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String

    /**
     * Send a structured messages list as a completion request.
     * Each message has "role" and "content" keys for proper chat formatting.
     *
     * Default implementation joins messages into a flat prompt for backward compatibility.
     */
    suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
        val flatPrompt = messages.joinToString("\n") { "${it["role"]}: ${it["content"]}" }
        return complete(flatPrompt)
    }

    /**
     * Stream a structured messages list token by token.
     * Each message has "role" and "content" keys for proper chat formatting.
     * [onReasoning] (v0.40.3+): 思维链增量独立回调 — 与可见正文 [onToken] 分流,
     * 由调用方决定如何展示。字段名按各厂商官方 API 文档归一 (v0.40.4, 见
     * ReasoningExtractor): OpenAI 兼容系 reasoning_content, Ollama 新版 /v1 兜底
     * reasoning, Anthropic 兼容格式 thinking_delta。
     *
     * Default implementation joins messages into a flat prompt and falls back
     * to [completeStreaming]; providers with native messages support override.
     */
    suspend fun completeStreamingWithMessages(
        messages: List<Map<String, String>>,
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)? = null
    ): String {
        val flatPrompt = messages.joinToString("\n") { "${it["role"]}: ${it["content"]}" }
        return completeStreaming(flatPrompt, onToken)
    }

    /**
     * Get provider metadata.
     */
    fun info(): ProviderInfo

    /**
     * Token usage from the most recent API call, if available.
     * Providers that track usage (e.g. AdaptiveLlmProvider) set this after each call.
     * Default is null (untracked / simulated providers).
     */
    val lastUsage: TokenUsage? get() = null

    /**
     * 最近一次 API 调用的思维链全文, 如果该调用返回了思考内容 (v0.40.4, 供观测;
     * UI 显示由流式 [onReasoning] 通道负责)。Providers that track usage also track this.
     * Default is null (untracked / simulated providers).
     */
    val lastReasoning: String? get() = null
}

data class ProviderInfo(
    val name: String,
    val model: String,
    val providerType: ProviderType
)

enum class ProviderType { LOCAL, REMOTE }
