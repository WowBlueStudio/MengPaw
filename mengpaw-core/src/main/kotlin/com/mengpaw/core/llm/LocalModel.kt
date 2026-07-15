package com.mengpaw.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Local LLM provider using on-device models.
 */
class LocalModel(
    private val modelPath: String,
    private val config: Map<String, Any> = emptyMap()
) : LlmProvider {

    private var loaded = false

    override suspend fun complete(prompt: String): String {
        ensureLoaded()
        // In production: runs ggml/llama.cpp inference
        return simulatedInference(prompt)
    }

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        ensureLoaded()
        val response = simulatedInference(prompt)
        response.forEach { char -> onToken(char.toString()) }
        return response
    }

    override fun info(): ProviderInfo = ProviderInfo(
        name = "LocalLLM",
        model = modelPath.substringAfterLast("/"),
        providerType = ProviderType.LOCAL
    )

    private fun ensureLoaded() {
        if (!loaded) {
            // Validate model file
            val file = java.io.File(modelPath)
            require(file.exists()) { "Model not found: $modelPath" }
            require(file.length() in 1_000_000..10_000_000_000) { "Invalid model size" }
            loaded = true
        }
    }

    private fun simulatedInference(prompt: String): String {
        return "[LocalModel simulation] Processed prompt (${prompt.length} chars)"
    }
}
