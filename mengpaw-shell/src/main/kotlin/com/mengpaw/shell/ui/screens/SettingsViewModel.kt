// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.core.llm.PromptEngine
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
        listOf(ModelInfo("glm-4-plus", "Chat"), ModelInfo("glm-4-flash", "Chat"), ModelInfo("glm-4v", "多模态"))),
    QWEN("Qwen (通义千问)", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus", "sk-",
        listOf(ModelInfo("qwen-plus", "Chat"), ModelInfo("qwen-max", "Chat"), ModelInfo("qwen-turbo", "Chat"), ModelInfo("qwen-vl-plus", "多模态"), ModelInfo("qwen-vl-max", "多模态"), ModelInfo("qwen-coder-turbo", "Chat"))),
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

data class SettingsState(
    val selectedProvider: LlmProviderPreset = LlmProviderPreset.OPENAI,
    val apiEndpoint: String = LlmProviderPreset.OPENAI.endpoint,
    val apiKey: String = "",
    val modelName: String = LlmProviderPreset.OPENAI.defaultModel,
    val maxSteps: Int = 50,
    val darkTheme: Boolean = false,
    val useSimulatedProvider: Boolean = true,
    val showApiKey: Boolean = false,
    val useChinese: Boolean = true,
    val agentLanguageMode: AgentLanguageMode = AgentLanguageMode.FOLLOW_UI,
    // API section state
    val apiSectionExpanded: Boolean = true,
    val savedProviders: List<SavedProvider> = emptyList(),
    val isTesting: Boolean = false,
    val balance: String = ""
) {
    val strings: AppStrings get() = if (useChinese) ChineseStrings else EnglishStrings

    /** Resolved Agent language: follow UI or user override. */
    val effectiveAgentLanguage: com.mengpaw.core.llm.PromptEngine.AgentLanguage get() = when (agentLanguageMode) {
        AgentLanguageMode.FOLLOW_UI -> PromptEngine.AgentLanguage.fromUiChinese(useChinese)
        AgentLanguageMode.CHINESE -> PromptEngine.AgentLanguage.CHINESE
        AgentLanguageMode.ENGLISH -> PromptEngine.AgentLanguage.ENGLISH
    }
}

/**
 * ViewModel for the settings screen.
 */
class SettingsViewModel : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Switch to a preset provider and auto-fill endpoint + model. */
    fun selectProvider(preset: LlmProviderPreset) {
        _state.value = _state.value.copy(
            selectedProvider = preset,
            apiEndpoint = preset.endpoint,
            modelName = preset.defaultModel
        )
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

    fun toggleSimulatedProvider() {
        _state.value = _state.value.copy(useSimulatedProvider = !_state.value.useSimulatedProvider)
    }

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
            delay(1200)
            _state.value = _state.value.copy(isTesting = false, balance = "1.23")
        }
    }

    fun resetToDefaults() {
        _state.value = SettingsState()
    }
}
