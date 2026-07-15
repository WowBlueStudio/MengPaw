package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preset LLM providers with known endpoints and models.
 */
enum class LlmProviderPreset(
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val apiKeyPrefix: String = ""
) {
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o", "sk-"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat", "sk-"),
    KIMI("Kimi (月之暗面)", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k", "sk-"),
    GLM("GLM (智谱)", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-plus", ""),
    QWEN("Qwen (通义千问)", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus", "sk-"),
    CUSTOM("Custom", "", "", "");
}

/**
 * Settings state for the app.
 */
data class SettingsState(
    val selectedProvider: LlmProviderPreset = LlmProviderPreset.OPENAI,
    val apiEndpoint: String = LlmProviderPreset.OPENAI.endpoint,
    val apiKey: String = "",
    val modelName: String = LlmProviderPreset.OPENAI.defaultModel,
    val maxSteps: Int = 50,
    val darkTheme: Boolean = false,
    val useSimulatedProvider: Boolean = true,
    val showApiKey: Boolean = false,
    val useChinese: Boolean = true
) {
    val strings: AppStrings
        get() = if (useChinese) ChineseStrings else EnglishStrings
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

    fun resetToDefaults() {
        _state.value = SettingsState()
    }
}
