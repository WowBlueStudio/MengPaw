// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.llm

/**
 * Core interface for LLM providers (local or remote).
 */
interface LlmProvider {
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
     * Get provider metadata.
     */
    fun info(): ProviderInfo

    /**
     * Token usage from the most recent API call, if available.
     * Providers that track usage (e.g. AdaptiveLlmProvider) set this after each call.
     * Default is null (untracked / simulated providers).
     */
    val lastUsage: TokenUsage? get() = null
}

data class ProviderInfo(
    val name: String,
    val model: String,
    val providerType: ProviderType
)

enum class ProviderType { LOCAL, REMOTE }
