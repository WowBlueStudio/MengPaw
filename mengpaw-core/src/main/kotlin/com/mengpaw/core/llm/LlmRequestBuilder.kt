package com.mengpaw.core.llm

import kotlinx.serialization.json.*

/**
 * Builds LLM API requests optimized for DeepSeek Prefix Caching.
 *
 * Key insight: messages[0] (system prompt) must be byte-identical
 * across all requests in a session for KV-Cache to hit.
 *
 * Cost impact:
 *   Cache miss = $0.14/1K tokens
 *   Cache hit  = $0.0028/1K tokens (50x cheaper)
 */
class LlmRequestBuilder(
    systemPrompt: String
) {
    /** Current system prompt — changing it resets the prefix cache. */
    @Volatile
    private var _systemPrompt: String = systemPrompt

    val currentSystemPrompt: String get() = _systemPrompt

    /**
     * Update the system prompt (e.g., when user switches Agent language).
     * Resets cache counters since the new prompt will be a cache miss.
     */
    fun updateSystemPrompt(newPrompt: String) {
        if (newPrompt != _systemPrompt) {
            _systemPrompt = newPrompt
            cumulativeCacheHitTokens = 0
            cumulativeCacheMissTokens = 0
        }
    }
    var cumulativeCacheHitTokens: Long = 0
        private set
    var cumulativeCacheMissTokens: Long = 0
        private set
    var lastPromptTokens: Int = 0
        private set

    /**
     * Build the messages list with the system prompt as messages[0].
     * This ensures the prefix is byte-identical across all requests in a session.
     */
    fun buildMessages(
        messages: List<Map<String, String>>
    ): List<Map<String, String>> {
        val fullMessages = mutableListOf<Map<String, String>>(
            mapOf("role" to "system", "content" to _systemPrompt)
        )
        fullMessages.addAll(messages)
        return fullMessages
    }

    /**
     * Build a complete JSON request body with the stable system prompt prefix.
     */
    fun buildRequest(
        messages: List<Map<String, String>>,
        tools: List<Map<String, Any>>? = null,
        streaming: Boolean = false,
        model: String,
        maxTokens: Int = 4096,
        temperature: Double = 0.7
    ): String {
        val fullMessages = buildMessages(messages)

        val json = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", streaming)
            putJsonArray("messages") {
                fullMessages.forEach { msg ->
                    addJsonObject {
                        put("role", msg["role"] ?: "user")
                        put("content", msg["content"] ?: "")
                    }
                }
            }
            if (tools != null && tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(toolToJsonElement(tool))
                    }
                }
            }
        }
        return json.toString()
    }

    private fun toolToJsonElement(tool: Map<String, Any>): JsonElement {
        return buildJsonObject {
            tool.forEach { (key, value) ->
                put(key, anyToJsonElement(value))
            }
        }
    }

    private fun anyToJsonElement(value: Any): JsonElement {
        return when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                @Suppress("UNCHECKED_CAST")
                (value as Map<String, Any>).forEach { (k, v) ->
                    put(k, anyToJsonElement(v))
                }
            }
            is List<*> -> buildJsonArray {
                value.forEach { item ->
                    if (item != null) {
                        add(anyToJsonElement(item))
                    }
                }
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}
