# Task: DeepSeek Prefix Caching Optimization for MengPaw

## Core Problem
MengPaw's LLM layer sends flat `[{role: "user", content: prompt}]` requests without a stable system prompt prefix. DeepSeek V4's KV-Cache is keyed by exact byte sequence of the request prefix — since every request has a different prefix, **cache hit rate is 0%**.

**Cost impact:** Cache miss = $0.14/1K tokens vs Cache hit = $0.0028/1K tokens — **50x difference**.

## Required Changes

### 1. Create `LlmRequestBuilder.kt` (NEW FILE)
`mengpaw-core/src/main/kotlin/com/mengpaw/core/llm/LlmRequestBuilder.kt`

Core principle: Every API request's `messages[0]` MUST be a byte-identical system prompt. This is the equivalent of Reasonix's `ImmutablePrefix` class.

```kotlin
package com.mengpaw.core.llm

/**
 * Builds LLM API requests optimized for DeepSeek Prefix Caching.
 * 
 * Key insight: messages[0] (system prompt) must be byte-identical
 * across all requests in a session for KV-Cache to hit.
 */
class LlmRequestBuilder(
    private val systemPrompt: String  // Fixed, stable system prompt
) {
    var cumulativeCacheHitTokens: Long = 0
    var cumulativeCacheMissTokens: Long = 0
    var lastPromptTokens: Int = 0

    fun buildRequest(
        messages: List<Map<String, String>>,
        tools: List<Map<String, Any>>? = null,
        streaming: Boolean = false,
        model: String,
        maxTokens: Int = 4096,
        temperature: Double = 0.7
    ): String {
        val fullMessages = mutableListOf<Map<String, String>>(
            mapOf("role" to "system", "content" to systemPrompt)
        )
        fullMessages.addAll(messages)
        // ... serialize to JSON
    }
}
```

### 2. Modify `AdaptiveLlmProvider.kt`
- Add `completeWithMessages(messages: List<Map<String, String>>): String`
- `buildRequestBody()` → accept messages list as parameter

### 3. Modify `RemoteApi.kt`
- Same as AdaptiveLlmProvider — add `completeWithMessages()`

### 4. Modify `LlmProvider.kt`
- Add `completeWithMessages()` with default implementation

### 5. Modify `AgentEngine.kt`
- Inject system prompt once at session init, not every ReAct step
- `buildConversation()` → return `List<Map<String, String>>` not flat string

### 6. Modify `SessionManager.kt`
- Add `getStructuredHistory(sessionId): List<Map<String, String>>`

## Constraints
1. **Backward compatible** — don't break `complete(prompt: String)`
2. **Don't modify test files** — all 44 tests must pass
3. **System prompt stability** — output of `buildSystemPrompt()` must be byte-identical within session
4. **Don't modify build.gradle.kts**

## Verification
```bash
cd D:\MengPaw
.\gradlew :mengpaw-core:testDebugUnitTest
.\gradlew :mengpaw-shell:assembleDebug
```
