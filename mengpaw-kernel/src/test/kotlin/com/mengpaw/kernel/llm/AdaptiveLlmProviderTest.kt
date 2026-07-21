// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.llm

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLlmProviderTest {

    @Test
    fun `constructor with default config`() {
        val provider = AdaptiveLlmProvider(
            apiEndpoint = "https://api.openai.com/v1/chat/completions",
            apiKey = "sk-test-key"
        )
        val info = provider.info()
        assertEquals("Openai", info.name)
        assertEquals("gpt-4.1", info.model)
        assertEquals(ProviderType.REMOTE, info.providerType)
    }

    @Test
    fun `provider type detection deepseek`() {
        val provider = AdaptiveLlmProvider(
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions",
            apiKey = "test-key"
        )
        assertEquals("Deepseek", provider.info().name)
    }

    @Test
    fun `provider type detection moonshot`() {
        val provider = AdaptiveLlmProvider(
            apiEndpoint = "https://api.moonshot.cn/v1/chat/completions",
            apiKey = "test-key"
        )
        assertEquals("Kimi", provider.info().name)
    }

    @Test
    fun `provider type detection bigmodel`() {
        val provider = AdaptiveLlmProvider(
            apiEndpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            apiKey = "test-key"
        )
        assertEquals("Glm", provider.info().name)
    }

    @Test
    fun `provider type detection qwen`() {
        val provider = AdaptiveLlmProvider(
            apiEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            apiKey = "test-key"
        )
        assertEquals("Qwen", provider.info().name)
    }

    @Test
    fun `provider type detection unknown defaults to openai`() {
        val provider = AdaptiveLlmProvider(
            apiEndpoint = "https://custom-llm.example.com/v1/chat",
            apiKey = "test-key"
        )
        assertEquals("Openai", provider.info().name)
    }

    @Test
    fun `adaptive config default values`() {
        val config = AdaptiveLlmProvider.AdaptiveConfig()
        assertEquals(4096, config.maxTokens)
        assertEquals(0.7, config.temperature, 0.001)
        assertEquals(60_000, config.timeoutMs)
        assertEquals(2, config.maxRetries)
        assertEquals(500, config.retryDelayMs)
        assertTrue(config.fallbacks.isEmpty())
    }

    @Test
    fun `config with fallbacks`() {
        val config = AdaptiveLlmProvider.AdaptiveConfig(
            maxRetries = 3,
            retryDelayMs = 200,
            fallbacks = listOf(
                FallbackEntry("https://api.deepseek.com/v1/chat/completions", "sk-ds-key", "deepseek-chat"),
                FallbackEntry("https://api.moonshot.cn/v1/chat/completions", "sk-kimi-key", "moonshot-v1")
            )
        )
        assertEquals(3, config.maxRetries)
        assertEquals(200, config.retryDelayMs)
        assertEquals(2, config.fallbacks.size)
        assertEquals("deepseek-chat", config.fallbacks[0].model)
        assertEquals("moonshot-v1", config.fallbacks[1].model)
    }

    @Test
    fun `fallback entry data class`() {
        val entry = FallbackEntry(
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions",
            apiKey = "sk-test-123",
            model = "deepseek-chat"
        )
        assertEquals("https://api.deepseek.com/v1/chat/completions", entry.apiEndpoint)
        assertEquals("sk-test-123", entry.apiKey)
        assertEquals("deepseek-chat", entry.model)
    }

    @Test
    fun `fallback entry default model`() {
        val entry = FallbackEntry(
            apiEndpoint = "https://api.openai.com/v1/chat/completions",
            apiKey = "sk-key"
        )
        assertEquals("gpt-4.1", entry.model)
    }

    @Test
    fun `llm fallback exhausted exception message`() {
        val cause = RuntimeException("Connection refused")
        val ex = LlmFallbackExhaustedException(
            "All LLM providers exhausted (primary + 2 fallbacks)",
            cause
        )
        assertEquals("All LLM providers exhausted (primary + 2 fallbacks)", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `llm api exception stores httpStatus`() {
        val ex = LlmApiException(429, "Rate limit exceeded")
        assertEquals(429, ex.httpStatus)
        assertEquals("Rate limit exceeded", ex.message)
    }

    @Test
    fun `close should not throw when no fallback providers`() {
        val provider = AdaptiveLlmProvider(
            apiEndpoint = "https://api.openai.com/v1/chat/completions",
            apiKey = "sk-test"
        )
        // close() should not throw even when fallback providers are never initialized
        provider.close()
    }
}
