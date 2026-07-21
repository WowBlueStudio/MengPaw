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
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o", "sk-",
        listOf(ModelInfo("gpt-4o", "多模态"), ModelInfo("gpt-4o-mini", "Chat"), ModelInfo("gpt-4-turbo", "Chat"), ModelInfo("gpt-3.5-turbo", "Chat"))),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat", "sk-",
        listOf(ModelInfo("deepseek-chat", "Chat"), ModelInfo("deepseek-reasoner", "Chat"))),
    KIMI("Kimi (月之暗面)", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k", "sk-",
        listOf(ModelInfo("moonshot-v1-8k", "Chat"), ModelInfo("moonshot-v1-32k", "Chat"), ModelInfo("moonshot-v1-128k", "Chat"))),
    GLM("GLM (智谱)", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-plus", "",
        listOf(ModelInfo("glm-4-plus", "Chat"), ModelInfo("glm-4-flash", "Chat"), ModelInfo("glm-4v-plus", "多模态"), ModelInfo("glm-4v-flash", "多模态"))),
    QWEN("Qwen (通义千问)", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus", "sk-",
        listOf(ModelInfo("qwen-plus", "Chat"), ModelInfo("qwen-max", "Chat"), ModelInfo("qwen-turbo", "Chat"), ModelInfo("qwen-vl-plus", "多模态"), ModelInfo("qwen-vl-max", "多模态"), ModelInfo("qwen-coder-turbo", "Chat"))),
    GROK("Grok (xAI)", "https://api.x.ai/v1/chat/completions", "grok-2", "xai-",
        listOf(ModelInfo("grok-3", "多模态"), ModelInfo("grok-2", "多模态"), ModelInfo("grok-2-vision", "多模态"))),
    VOLCANO("火山引擎 (豆包)", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-pro-32k", "",
        listOf(ModelInfo("doubao-pro-32k", "Chat"), ModelInfo("doubao-pro-128k", "Chat"), ModelInfo("doubao-lite-32k", "Chat"), ModelInfo("doubao-vision-pro", "多模态"),
            // 注意: 火山引擎 ARK 需先在控制台创建推理接入点, 将接入点 ID 作为 model 参数
            ModelInfo("(需创建接入点)", "提示"))),
    OPENMODEL("OpenModel", "https://api.openmodel.ai/v1/chat/completions", "deepseek-v4-flash", "sk-",
        listOf(ModelInfo("deepseek-v4-flash", "Chat"), ModelInfo("deepseek-r1", "Chat"))),
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

    /** Switch to a preset provider and auto-fill endpoint + model. Triggers model list fetch. */
    fun selectProvider(preset: LlmProviderPreset) {
        _state.value = _state.value.copy(
            selectedProvider = preset,
            apiEndpoint = preset.endpoint,
            modelName = preset.defaultModel
        )
        fetchRemoteModels()
    }

    /** Auto-fetch available models from the provider's /models endpoint. */
    fun fetchRemoteModels() {
        val ep = _state.value.apiEndpoint
        if (ep.isBlank()) return
        viewModelScope.launch {
            try {
                val base = ep.substringBefore("/chat/completions").substringBefore("/v1/chat")
                val url = "$base/v1/models"
                val client = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                client.connectTimeout = 5000; client.readTimeout = 10000
                val key = _state.value.apiKey
                if (key.isNotBlank()) client.setRequestProperty("Authorization", "Bearer $key")
                val body = client.inputStream.bufferedReader().readText()
                client.disconnect()
                // Parse OpenAI-format model list: {"data":[{"id":"gpt-4o"},...]}
                val models = try {
                    Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                        .map { it.groupValues[1] }
                        .filter { !it.contains(":") && it.length < 80 }
                        .toList()
                } catch (_: Exception) { emptyList() }
                if (models.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        remoteModels = models,
                        remoteModelsFetched = true
                    )
                }
            } catch (_: Exception) {
                // API unreachable — keep preset models
            }
        }
    }

    fun updateApiEndpoint(endpoint: String) {
        _state.value = _state.value.copy(apiEndpoint = endpoint)
    }

    fun updateApiKey(key: String) {
        _state.value = _state.value.copy(apiKey = key)
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
        // SECURITY: Persist API key to encrypted Vault so DreamWorker can read it securely
        vault.store("api_key", _state.value.apiKey)
        vault.store("api_endpoint", _state.value.apiEndpoint)
        vault.store("model_name", _state.value.modelName)
        _state.value = _state.value.copy(savedProviders = existing, apiSectionExpanded = false)
    }

    fun removeProvider(preset: LlmProviderPreset) {
        _state.value = _state.value.copy(
            savedProviders = _state.value.savedProviders.filter { it.preset != preset }
        )
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
    }
}
