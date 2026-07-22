// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.core.security.Vault
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers; import kotlinx.coroutines.launch; import kotlinx.coroutines.withContext

/**
 * Preset LLM providers with known endpoints and models.
 */
data class ModelInfo(val name: String, val type: String) // type: "Chat" or "多模态"

enum class LlmProviderPreset(
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val apiKeyPrefix: String = "",
    val models: List<ModelInfo> = emptyList()
) {
    // ═══ Presets verified against official docs — 2026-07-21 ═══
    // Only top models listed here; full list fetched from API on key entry.
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5.4", "sk-",
        listOf(ModelInfo("gpt-5.4", "旗舰"), ModelInfo("gpt-5.4-mini", "快速"), ModelInfo("gpt-5.4-nano", "轻量"),
            ModelInfo("gpt-5", "前代"), ModelInfo("o4-mini", "思维链"))),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-v4-flash", "sk-",
        listOf(ModelInfo("deepseek-v4-flash", "快速"), ModelInfo("deepseek-v4-pro", "思维链"))),
    KIMI("Kimi (月之暗面)", "https://api.moonshot.cn/v1/chat/completions", "kimi-k3", "sk-",
        listOf(ModelInfo("kimi-k3", "旗舰·1M上下文"), ModelInfo("kimi-k2.7-code", "Coding"),
            ModelInfo("kimi-k2.6", "通用"), ModelInfo("kimi-k2.7-code-highspeed", "高速Coding"))),
    GLM("GLM (智谱)", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-5.2", "",
        listOf(ModelInfo("glm-5.2", "旗舰·1M上下文"), ModelInfo("glm-5.1", "Coding"),
            ModelInfo("glm-5", "前代"), ModelInfo("glm-5-turbo", "高速"), ModelInfo("glm-5v-turbo", "多模态"))),
    QWEN("Qwen (通义千问)", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3.7-max", "sk-",
        listOf(ModelInfo("qwen3.7-max", "旗舰·1M上下文"), ModelInfo("qwen3.6-35b-a3b", "开源MoE"),
            ModelInfo("qwen3.5-plus", "均衡"), ModelInfo("qwen-flash", "快速"),
            ModelInfo("qwen3-coder-plus", "Coding"), ModelInfo("qwq-plus", "思维链"),
            ModelInfo("qwen3-vl-plus", "多模态"), ModelInfo("qwen3-omni-flash", "全模态"))),
    GROK("Grok (xAI)", "https://api.x.ai/v1/chat/completions", "grok-4.3", "xai-",
        listOf(ModelInfo("grok-4.5", "旗舰"), ModelInfo("grok-4.3", "推荐·1M上下文"),
            ModelInfo("grok-4.20-reasoning", "思维链"), ModelInfo("grok-4.1-fast-non-reasoning", "快速"),
            ModelInfo("grok-build-0.1", "Coding"))),
    VOLCANO("火山引擎 (豆包)", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-seed-2.0-pro", "",
        listOf(ModelInfo("doubao-seed-2.0-pro", "旗舰"), ModelInfo("doubao-seed-2.0-lite", "均衡"),
            ModelInfo("doubao-seed-2.0-mini", "轻量"), ModelInfo("doubao-seed-1.8", "前代"),
            ModelInfo("doubao-seed-1.6-flash", "快速"), ModelInfo("doubao-seed-1.6-thinking", "思维链"),
            ModelInfo("deepseek-v3-2", "DeepSeek托管"), ModelInfo("glm-4.7", "GLM托管"),
            ModelInfo("(需创建接入点 ep-xxx)", "提示"))),
    OPENMODEL("OpenModel", "https://api.openmodel.ai/v1/chat/completions", "deepseek-v4-flash", "sk-",
        listOf(ModelInfo("deepseek-v4-pro", "思维链"), ModelInfo("deepseek-v4-flash", "快速"),
            ModelInfo("qwen3.7-max", "Qwen托管"), ModelInfo("gpt-5.4-mini", "OpenAI托管"),
            ModelInfo("kimi-k3", "Kimi托管"), ModelInfo("glm-5.2", "GLM托管"),
            ModelInfo("grok-4.5", "Grok托管"), ModelInfo("(更多模型见API返回)", "提示"))),
    SELF_HOSTED("Self-Hosted (自建)", "http://192.168.1.100:9877/v1/chat/completions", "local-model", "",
        listOf(ModelInfo("local-model", "Chat"), ModelInfo("qwen2.5:7b", "Chat"), ModelInfo("llama3.1:8b", "Chat"))),
    CUSTOM("Custom", "", "", "", emptyList());
}

fun LlmProviderPreset.modelListDisplay(): List<ModelInfo> =
    if (models.size <= 5) models else models.take(5)

/**
 * Settings state for the app.
 */
data class SavedProvider(
    val preset: LlmProviderPreset,
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val balance: String = ""
)

/** Agent language modes for controlling LLM output language. */
enum class AgentLanguageMode(val labelKey: String) { FOLLOW_UI("followUi"), CHINESE("chinese"), ENGLISH("english") }

/** Agent loop / execution mode. */
enum class LoopMode(val label: String, val desc: String) {
    GOAL("Goal 模式", "单目标驱动，完成即停"),
    MISSION("Mission 模式", "建立临时子 Agent 分解任务链，逐步执行"),
    MISSION_PLUS("Mission+ 模式", "协调跨 Agent、跨框架、跨设备的多 Agent 协同处理复杂任务")
}

data class SettingsState(
    val selectedProvider: LlmProviderPreset = LlmProviderPreset.OPENAI,
    val apiEndpoint: String = LlmProviderPreset.OPENAI.endpoint,
    val apiKey: String = "",
    val modelName: String = LlmProviderPreset.OPENAI.defaultModel,
    val remoteModels: List<String> = emptyList(),
    val remoteModelsFetched: Boolean = false,
    val maxSteps: Int = 50,
    val commandTimeoutSec: Int = 60,
    val timezone: String = java.util.TimeZone.getDefault().id,
    val contextStrategy: String = "default",
    val memoryBackend: String = "memory-plugin",
    val darkTheme: Boolean = false,
    val showApiKey: Boolean = false,
    val useChinese: Boolean = true,
    val agentLanguageMode: AgentLanguageMode = AgentLanguageMode.FOLLOW_UI,
    val loopMode: LoopMode = LoopMode.GOAL,
    // API section state
    val apiSectionExpanded: Boolean = true,
    val savedProviders: List<SavedProvider> = emptyList(),
    val isTesting: Boolean = false,
    val balance: String = ""
) {
    val strings: AppStrings get() = if (useChinese) ChineseStrings else EnglishStrings

    /** Resolved Agent language: follow UI or user override. */
    val effectiveAgentLanguage: com.mengpaw.kernel.llm.PromptEngine.AgentLanguage get() = when (agentLanguageMode) {
        AgentLanguageMode.FOLLOW_UI -> PromptEngine.AgentLanguage.fromUiChinese(useChinese)
        AgentLanguageMode.CHINESE -> PromptEngine.AgentLanguage.CHINESE
        AgentLanguageMode.ENGLISH -> PromptEngine.AgentLanguage.ENGLISH
    }
}

/**
 * ViewModel for the settings screen.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    // SECURITY: Use encrypted Vault for API key persistence
    private val vault = Vault(application)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        loadSavedProviders()
    }

    /** Restore saved providers from encrypted Vault on app startup. */
    private fun loadSavedProviders() {
        if (!vault.isAvailable) return
        try {
            val json = vault.retrieve(VAULT_KEY_PROVIDERS)
            if (json.isNullOrBlank()) {
                // Migration: old single-key format → new multi-provider format
                migrateLegacyKey()
                return
            }
            val arr = org.json.JSONArray(json)
            val providers = mutableListOf<SavedProvider>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val preset = try {
                    LlmProviderPreset.valueOf(obj.getString("preset"))
                } catch (_: Exception) { LlmProviderPreset.CUSTOM }
                providers.add(SavedProvider(
                    preset = preset,
                    apiKey = obj.optString("apiKey", ""),
                    endpoint = obj.optString("endpoint", ""),
                    model = obj.optString("model", ""),
                    balance = obj.optString("balance", "")
                ))
            }
            if (providers.isNotEmpty()) {
                _state.value = _state.value.copy(savedProviders = providers)
            }
        } catch (_: Exception) {
            // Corrupted data or first launch — start fresh
            migrateLegacyKey()
        }
    }

    /** Migrate old single-key Vault entries into the new multi-provider format. */
    private fun migrateLegacyKey() {
        val oldApiKey = vault.retrieve("api_key") ?: return
        val oldEndpoint = vault.retrieve("api_endpoint") ?: ""
        val oldModel = vault.retrieve("model_name") ?: ""
        if (oldApiKey.isBlank()) return

        // Detect preset from endpoint
        val preset = LlmProviderPreset.entries.firstOrNull { it.endpoint == oldEndpoint }
            ?: LlmProviderPreset.CUSTOM
        val saved = SavedProvider(preset, oldApiKey, oldEndpoint, oldModel)
        _state.value = _state.value.copy(savedProviders = listOf(saved))

        // Save in new format, then clear old keys
        persistProviders(listOf(saved))
        try { vault.remove("api_key") } catch (_: Exception) {}
        try { vault.remove("api_endpoint") } catch (_: Exception) {}
        try { vault.remove("model_name") } catch (_: Exception) {}
    }

    /** Serialize and persist all saved providers to encrypted Vault. */
    private fun persistProviders(providers: List<SavedProvider>) {
        if (!vault.isAvailable) return
        val arr = org.json.JSONArray()
        providers.forEach { p ->
            val obj = org.json.JSONObject()
            obj.put("preset", p.preset.name)
            obj.put("apiKey", p.apiKey)
            obj.put("endpoint", p.endpoint)
            obj.put("model", p.model)
            obj.put("balance", p.balance)
            arr.put(obj)
        }
        vault.store(VAULT_KEY_PROVIDERS, arr.toString())
    }

    companion object {
        private const val VAULT_KEY_PROVIDERS = "saved_providers_json"
    }

    /** Switch to a preset provider and auto-fill endpoint + model. Triggers model list fetch. */
    fun selectProvider(preset: LlmProviderPreset) {
        _state.value = _state.value.copy(
            selectedProvider = preset,
            apiEndpoint = preset.endpoint,
            modelName = preset.defaultModel
        )
        fetchRemoteModels()
    }

    /**
     * Auto-fetch available models from the provider's GET /models endpoint.
     *
     * Most providers expose an OpenAI-compatible `GET /v1/models` (or `/models`)
     * that returns `{"data":[{"id":"model-name"},...]}`. We try both paths,
     * filter out non-chat models (embedding, tts, whisper, dall-e, etc.), and
     * auto-select the first matching model if the current one isn't in the list.
     */
    fun fetchRemoteModels() {
        val ep = _state.value.apiEndpoint
        val key = _state.value.apiKey
        if (ep.isBlank() || key.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val base = ep.substringBefore("/chat/completions")
                .substringBefore("/v1/chat")
                .substringBefore("/compatible-mode/v1")

            // Try both common paths — OpenAI uses /v1/models, some use /models
            val candidatePaths = listOf("$base/v1/models", "$base/models")
            var models: List<String> = emptyList()

            for (url in candidatePaths) {
                try {
                    val client = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    client.connectTimeout = 5000; client.readTimeout = 10000
                    client.setRequestProperty("Authorization", "Bearer $key")
                    val body = client.inputStream.bufferedReader().readText()
                    client.disconnect()

                    val parsed = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                        .map { it.groupValues[1] }
                        .filter { id ->
                            // Exclude non-chat models
                            id.length < 80 && !id.contains(":") &&
                            !id.startsWith("dall-e") && !id.startsWith("whisper") &&
                            !id.startsWith("tts") && !id.contains("embedding") &&
                            !id.contains("moderation") && !id.contains("babbage") &&
                            !id.contains("davinci")
                        }
                        .toList()

                    if (parsed.isNotEmpty()) {
                        models = parsed
                        break // Got results, stop trying other URLs
                    }
                } catch (_: Exception) {
                    // Try next URL
                }
            }

            if (models.isNotEmpty()) {
                val currentModel = _state.value.modelName
                val currentInList = models.any { it.equals(currentModel, ignoreCase = true) }

                _state.value = _state.value.copy(
                    remoteModels = models,
                    remoteModelsFetched = true,
                    // Auto-select first model if current one isn't in the fetched list
                    modelName = if (currentInList) currentModel else models.first()
                )
            }
        }
    }

    fun updateApiEndpoint(endpoint: String) {
        _state.value = _state.value.copy(apiEndpoint = endpoint)
        // Auto-detect models when endpoint changes and key is already set
        if (_state.value.apiKey.isNotBlank()) fetchRemoteModels()
    }

    fun updateApiKey(key: String) {
        _state.value = _state.value.copy(apiKey = key)
        // Auto-detect models when key is entered and endpoint is set
        if (key.isNotBlank()) fetchRemoteModels()
    }

    fun updateModelName(model: String) {
        _state.value = _state.value.copy(modelName = model)
    }

    fun updateMaxSteps(steps: Int) {
        _state.value = _state.value.copy(maxSteps = steps.coerceIn(1, 200))
    }

    fun toggleDarkTheme() {
        _state.value = _state.value.copy(darkTheme = !_state.value.darkTheme)
    }

    fun updateCommandTimeout(sec: Int) {
        _state.value = _state.value.copy(commandTimeoutSec = sec.coerceIn(10, 600))
    }
    fun updateTimezone(tz: String) { _state.value = _state.value.copy(timezone = tz) }
    fun updateContextStrategy(s: String) { _state.value = _state.value.copy(contextStrategy = s) }
    fun updateMemoryBackend(b: String) { _state.value = _state.value.copy(memoryBackend = b) }

    fun toggleShowApiKey() {
        _state.value = _state.value.copy(showApiKey = !_state.value.showApiKey)
    }

    fun toggleLanguage() {
        _state.value = _state.value.copy(useChinese = !_state.value.useChinese)
    }

    fun cycleAgentLanguage() {
        val modes = AgentLanguageMode.entries
        val next = modes[(modes.indexOf(_state.value.agentLanguageMode) + 1) % modes.size]
        _state.value = _state.value.copy(agentLanguageMode = next)
    }

    fun setLoopMode(mode: LoopMode) {
        _state.value = _state.value.copy(loopMode = mode)
    }

    fun toggleApiSection() {
        _state.value = _state.value.copy(apiSectionExpanded = !_state.value.apiSectionExpanded)
    }

    fun expandForNewProvider() {
        _state.value = _state.value.copy(
            apiSectionExpanded = true, apiKey = "", balance = "",
            selectedProvider = LlmProviderPreset.OPENAI,
            apiEndpoint = LlmProviderPreset.OPENAI.endpoint,
            modelName = LlmProviderPreset.OPENAI.defaultModel
        )
    }

    fun saveApiKey() {
        val existing = _state.value.savedProviders.toMutableList()
        val entry = SavedProvider(
            preset = _state.value.selectedProvider,
            apiKey = _state.value.apiKey,
            endpoint = _state.value.apiEndpoint,
            model = _state.value.modelName,
            balance = _state.value.balance
        )
        existing.removeAll { it.preset == entry.preset }
        existing.add(entry)
        // Persist all providers to encrypted Vault
        persistProviders(existing)
        // Also update legacy keys for DreamWorker backward compat
        vault.store("api_key", _state.value.apiKey)
        vault.store("api_endpoint", _state.value.apiEndpoint)
        vault.store("model_name", _state.value.modelName)
        _state.value = _state.value.copy(savedProviders = existing, apiSectionExpanded = false)
    }

    fun removeProvider(preset: LlmProviderPreset) {
        val updated = _state.value.savedProviders.filter { it.preset != preset }
        _state.value = _state.value.copy(savedProviders = updated)
        persistProviders(updated)
    }

    fun editProvider(saved: SavedProvider) {
        _state.value = _state.value.copy(
            selectedProvider = saved.preset,
            apiKey = saved.apiKey,
            apiEndpoint = saved.endpoint,
            modelName = saved.model,
            balance = saved.balance,
            apiSectionExpanded = true
        )
    }

    fun testConnection() {
        _state.value = _state.value.copy(isTesting = true)
        viewModelScope.launch {
            try {
                val ep = _state.value.apiEndpoint
                if (ep.isBlank()) { _state.value = _state.value.copy(isTesting = false, balance = "N/A"); return@launch }
                val base = ep.substringBefore("/chat/completions").substringBefore("/v1/chat")
                val url = java.net.URL("$base/v1/models")
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 10000
                    val key = _state.value.apiKey
                    if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
                    val code = conn.responseCode
                    conn.disconnect()
                    val result = if (code in 200..299) "OK" else "Err $code"
                    _state.value = _state.value.copy(isTesting = false, balance = result)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isTesting = false, balance = "Error")
            }
        }
    }

    fun resetToDefaults() {
        _state.value = SettingsState()
        try { vault.clear() } catch (_: Exception) {}
    }
}
