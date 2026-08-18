// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.core.security.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers; import kotlinx.coroutines.launch; import kotlinx.coroutines.withContext

// 数据模型 → SettingsModels.kt; Vault 持久化 → SettingsProviderStore.kt;
// 远程探测 → SettingsRemote.kt; CONFIG 文件 → SettingsConfigFiles.kt (2026-08-06, 批次4)

/**
 * ViewModel for the settings screen.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    // SECURITY: Use encrypted Vault for API key persistence
    private val vault = Vault(application)
    private val providerStore = SettingsProviderStore(vault)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        loadSavedProviders()
        // 自动翻译 opt-in: 配置文件不存在 → 默认关闭(用户未主动开启)
        try {
            val v = SettingsConfigFiles.readText("auto_translate")
            if (v != null) _state.value = _state.value.copy(autoTranslate = v == "true")
        } catch (_: Exception) {}
        // 省电偏好恢复 — 此前为 UI 局部 state，切换即丢且重启必回 false
        try {
            val v = SettingsConfigFiles.readText("power_saver")
            if (v != null) _state.value = _state.value.copy(powerSaverEnabled = v == "true")
        } catch (_: Exception) {}
        // P0 fix: 主题/语言持久化恢复 — 此前 cycleThemeMode/toggleLanguage 只改内存,
        // 重启必回 LIGHT+中文默认
        try {
            val v = SettingsConfigFiles.readText("theme_mode")
            if (v != null) {
                ThemeMode.entries.firstOrNull { it.name == v }?.let {
                    _state.value = _state.value.copy(themeMode = it)
                }
            }
        } catch (_: Exception) {}
        try {
            val v = SettingsConfigFiles.readText("use_chinese")
            if (v != null) {
                _state.value = _state.value.copy(useChinese = v != "false")
            }
        } catch (_: Exception) {}
        // Tavily key 已配置状态 — 供框架设置页展示 (明文不进入 UI 文本/状态)
        try {
            _state.value = _state.value.copy(
                tavilyKeyConfigured = com.mengpaw.plugin.tavily.TavilyPlugin.isApiKeyConfigured()
            )
        } catch (_: Exception) {}
    }

    /** Returns the first saved provider, or null if none configured. */
    fun firstSavedProvider(): SavedProvider? = _state.value.savedProviders.firstOrNull()

    /** Restore saved providers from encrypted Vault on app startup. */
    private fun loadSavedProviders() {
        when (val result = providerStore.restore()) {
            is SettingsProviderStore.RestoreResult.Loaded -> {
                if (result.providers.isNotEmpty() || result.roles.isNotEmpty()) {
                    _state.value = _state.value.copy(savedProviders = result.providers, swarmRoles = result.roles)
                }
            }
            is SettingsProviderStore.RestoreResult.Migrated -> {
                result.provider?.let { _state.value = _state.value.copy(savedProviders = listOf(it)) }
            }
            SettingsProviderStore.RestoreResult.Nothing -> {}
        }
    }

    /** 设置/清除角色模型路由（provider=null 移除该角色，回退主模型）。 */
    fun setSwarmRole(role: String, provider: SavedProvider?) {
        val roles = if (provider == null) _state.value.swarmRoles - role
        else _state.value.swarmRoles + (role to provider)
        _state.value = _state.value.copy(swarmRoles = roles)
        providerStore.persistSwarmRoles(roles)
    }

    companion object {
        /** Fleet/火种角色模型路由 — 角色键与内核 SwarmRoles 单一事实源对齐。 */
        val SWARM_ROLES: List<String> = com.mengpaw.kernel.agent.SwarmRoles.ALL

        /** 角色显示名（UI）。 */
        fun roleLabel(role: String): String = when (role) {
            com.mengpaw.kernel.agent.SwarmRoles.PLANNER -> "规划器 (拆解)"
            com.mengpaw.kernel.agent.SwarmRoles.WORKER -> "执行器 (worker)"
            com.mengpaw.kernel.agent.SwarmRoles.VERIFIER -> "验收器 (verifier)"
            com.mengpaw.kernel.agent.SwarmRoles.SYNTHESIZER -> "合成器 (synthesizer)"
            com.mengpaw.kernel.agent.SwarmRoles.WORKER_ALT -> "备用执行器 (worker.alt)"
            else -> role
        }
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

    /** Debounce job for fetchRemoteModels — prevents rapid-fire on paste/keystroke. */
    private var fetchModelsJob: kotlinx.coroutines.Job? = null

    /**
     * Auto-fetch available models from the provider's GET /models endpoint.
     * Debounced: cancels previous request if re-invoked within 500ms.
     * Runs on IO dispatcher with short timeouts to avoid ANR.
     */
    fun fetchRemoteModels() {
        val ep = _state.value.apiEndpoint
        val key = _state.value.apiKey
        if (ep.isBlank() || key.isBlank()) return

        // Debounce: cancel pending fetch, restart after 500ms quiet period
        fetchModelsJob?.cancel()
        fetchModelsJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500) // wait for user to finish typing
            try {
                val models = fetchModelsFromEndpoint(ep, key)
                if (models.isNotEmpty()) {
                    val currentModel = _state.value.modelName
                    val currentInList = models.any { it.equals(currentModel, ignoreCase = true) }
                    _state.value = _state.value.copy(
                        remoteModels = models,
                        remoteModelsFetched = true,
                        modelName = if (currentInList) currentModel else models.first()
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancelled by debounce — normal, ignore
            }
        }
    }

    fun updateApiEndpoint(endpoint: String) {
        _state.value = _state.value.copy(apiEndpoint = endpoint)
        // Auto-detect models when endpoint changes and key is already set
        // Model list is fetched on-demand via refreshModels() — no auto-fetch
    }

    fun updateApiKey(key: String) {
        _state.value = _state.value.copy(apiKey = key)
        // Model list is fetched on-demand via refreshModels() — no auto-fetch on paste
    }

    /** Manually refresh model list from the provider's API. Called by UI button. */
    fun refreshModels() {
        fetchRemoteModels()
    }

    fun updateModelName(model: String) {
        _state.value = _state.value.copy(modelName = model)
    }

    fun updateMaxSteps(steps: Int) {
        _state.value = _state.value.copy(maxSteps = steps.coerceIn(1, 200))
    }

    fun updateLlmMaxConcurrency(count: Int) {
        val clamped = count.coerceIn(1, 50)
        _state.value = _state.value.copy(llmMaxConcurrency = clamped)
        com.mengpaw.kernel.llm.LlmRateLimiter.maxConcurrency = clamped
    }

    fun cycleThemeMode() {
        val modes = ThemeMode.entries
        val next = modes[(modes.indexOf(_state.value.themeMode) + 1) % modes.size]
        _state.value = _state.value.copy(themeMode = next)
        // P0 fix: 持久化 — 此前重启即回 LIGHT
        try {
            SettingsConfigFiles.writeText("theme_mode", next.name)
        } catch (_: Exception) {}
    }

    fun cycleBackgroundMode() {
        val modes = BackgroundMode.entries
        val next = modes[(modes.indexOf(_state.value.backgroundMode) + 1) % modes.size]
        _state.value = _state.value.copy(backgroundMode = next)
        // 写入文件让 ShellService 读取
        try {
            SettingsConfigFiles.writeText("background_mode", next.name)
        } catch (_: Exception) {}
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

    // ── Tavily 搜索 API key (框架设置页) ───────────────────────────────
    /** 更新 Tavily key 输入框临时值 (仅内存, 不落盘)。 */
    fun updateTavilyApiKey(v: String) {
        _state.value = _state.value.copy(tavilyApiKeyInput = v)
    }

    /** 密码框明文切换。 */
    fun toggleShowTavilyKey() {
        _state.value = _state.value.copy(showTavilyKey = !_state.value.showTavilyKey)
    }

    /** 保存 Tavily key — 走插件混淆落盘, 保存后清空输入框并刷新已配置状态。 */
    fun saveTavilyApiKey() {
        val key = _state.value.tavilyApiKeyInput
        if (key.isBlank()) return
        val ok = com.mengpaw.plugin.tavily.TavilyPlugin.saveApiKeyFromUi(key)
        _state.value = _state.value.copy(
            tavilyApiKeyInput = "",
            tavilyKeyConfigured = ok || com.mengpaw.plugin.tavily.TavilyPlugin.isApiKeyConfigured()
        )
    }

    /** 清除已保存的 Tavily key — 空串写入覆盖原配置。 */
    fun clearTavilyApiKey() {
        com.mengpaw.plugin.tavily.TavilyPlugin.saveApiKeyFromUi("")
        _state.value = _state.value.copy(tavilyApiKeyInput = "", tavilyKeyConfigured = false)
    }

    /** 自动翻译开关 — 默认关, 用户主动开启才启用 Google 翻译中间件. */
    fun toggleAutoTranslate() {
        val next = !_state.value.autoTranslate
        _state.value = _state.value.copy(autoTranslate = next)
        try {
            SettingsConfigFiles.writeText("auto_translate", next.toString())
        } catch (_: Exception) {}
    }

    /** 后台省电开关 — 修复: 此前仅 UI 局部 remember 状态，切换不持久化且不接任何逻辑。现持久化到 CONFIG/power_saver。 */
    fun togglePowerSaver() {
        val next = !_state.value.powerSaverEnabled
        _state.value = _state.value.copy(powerSaverEnabled = next)
        try {
            SettingsConfigFiles.writeText("power_saver", next.toString())
        } catch (_: Exception) {}
    }

    fun toggleLanguage() {
        val next = !_state.value.useChinese
        _state.value = _state.value.copy(useChinese = next)
        // P0 fix: 持久化 — 此前重启即回中文
        try {
            SettingsConfigFiles.writeText("use_chinese", next.toString())
        } catch (_: Exception) {}
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
        providerStore.persistProviders(existing)
        // Also update legacy keys for DreamWorker backward compat
        vault.store("api_key", _state.value.apiKey)
        vault.store("api_endpoint", _state.value.apiEndpoint)
        vault.store("model_name", _state.value.modelName)
        _state.value = _state.value.copy(savedProviders = existing, apiSectionExpanded = false)
    }

    fun removeProvider(preset: LlmProviderPreset) {
        val updated = _state.value.savedProviders.filter { it.preset != preset }
        _state.value = _state.value.copy(savedProviders = updated)
        providerStore.persistProviders(updated)
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
                withContext(Dispatchers.IO) {
                    val result = testConnectionResult(ep, _state.value.apiKey)
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
