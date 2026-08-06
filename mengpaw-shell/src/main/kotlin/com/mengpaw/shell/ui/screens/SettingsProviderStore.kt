// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.core.security.Vault
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// ── 设置持久化 (Vault 加密存储) — 拆自 SettingsViewModel.kt (2026-08-06, 批次4) ──

/**
 * Provider 配置的 Vault 加密存取: 多 provider JSON + 角色模型路由独立 key +
 * 旧单键格式迁移。行为与原 SettingsViewModel 私有方法逐行对齐 (批次4 纯机械拆分)。
 */
internal class SettingsProviderStore(private val vault: Vault) {

    sealed interface RestoreResult {
        /** 新格式数据解码成功 — VM 按非空条件应用。 */
        data class Loaded(val providers: List<SavedProvider>, val roles: Map<String, SavedProvider>) : RestoreResult
        /** 无新格式数据/损坏 — 已执行旧键迁移, provider 为迁移出的条目 (可能为 null)。 */
        data class Migrated(val provider: SavedProvider?) : RestoreResult
        /** Vault 不可用 — 不迁移不改状态。 */
        object Nothing : RestoreResult
    }

    /** 恢复全部已保存 provider + 角色路由 (含旧格式迁移), 语义同原 loadSavedProviders。 */
    fun restore(): RestoreResult {
        if (!vault.isAvailable) return RestoreResult.Nothing
        try {
            val rawJson = vault.retrieve(VAULT_KEY_PROVIDERS)
            if (rawJson.isNullOrBlank()) {
                // Migration: old single-key format → new multi-provider format
                return RestoreResult.Migrated(migrateLegacyKey())
            }
            val jsonList = settingsAppJson.decodeFromString<List<SavedProviderJson>>(rawJson)
            val providers = jsonList.map { p ->
                SavedProvider(
                    preset = try { LlmProviderPreset.valueOf(p.preset) } catch (_: Exception) { LlmProviderPreset.CUSTOM },
                    apiKey = p.apiKey,
                    endpoint = p.endpoint,
                    model = p.model,
                    balance = p.balance
                )
            }
            // 角色模型路由配置（独立 Vault key，损坏则静默跳过）
            var roles = emptyMap<String, SavedProvider>()
            try {
                val rolesRaw = vault.retrieve(VAULT_KEY_SWARM_ROLES)
                if (!rolesRaw.isNullOrBlank()) {
                    val rolesJson = settingsAppJson.decodeFromString<Map<String, SavedProviderJson>>(rolesRaw)
                    roles = rolesJson.mapNotNull { (role, p) ->
                        val sp = SavedProvider(
                            preset = try { LlmProviderPreset.valueOf(p.preset) } catch (_: Exception) { LlmProviderPreset.CUSTOM },
                            apiKey = p.apiKey, endpoint = p.endpoint, model = p.model, balance = p.balance
                        )
                        if (sp.endpoint.isBlank()) null else role to sp
                    }.toMap()
                }
            } catch (e: Exception) {
                com.mengpaw.kernel.KernelLog.w("SettingsVM", "角色路由配置解析失败（损坏则跳过）: ${e.message}")
            }
            return RestoreResult.Loaded(providers, roles)
        } catch (_: Exception) {
            // Corrupted data or first launch — start fresh
            return RestoreResult.Migrated(migrateLegacyKey())
        }
    }

    /** Migrate old single-key Vault entries into the new multi-provider format. */
    private fun migrateLegacyKey(): SavedProvider? {
        val oldApiKey = vault.retrieve("api_key") ?: return null
        val oldEndpoint = vault.retrieve("api_endpoint") ?: ""
        val oldModel = vault.retrieve("model_name") ?: ""
        if (oldApiKey.isBlank()) return null

        // Detect preset from endpoint
        val preset = LlmProviderPreset.entries.firstOrNull { it.endpoint == oldEndpoint }
            ?: LlmProviderPreset.CUSTOM
        val saved = SavedProvider(preset, oldApiKey, oldEndpoint, oldModel)

        // Save in new format, then clear old keys
        persistProviders(listOf(saved))
        try { vault.remove("api_key") } catch (_: Exception) {}
        try { vault.remove("api_endpoint") } catch (_: Exception) {}
        try { vault.remove("model_name") } catch (_: Exception) {}
        return saved
    }

    /** Serialize and persist all saved providers to encrypted Vault. */
    fun persistProviders(providers: List<SavedProvider>) {
        if (!vault.isAvailable) return
        val jsonList = providers.map { p ->
            SavedProviderJson(
                preset = p.preset.name,
                apiKey = p.apiKey,
                endpoint = p.endpoint,
                model = p.model,
                balance = p.balance
            )
        }
        vault.store(VAULT_KEY_PROVIDERS,  settingsAppJson.encodeToString(ListSerializer(SavedProviderJson.serializer()), jsonList))
    }

    /** Serialize and persist role routing to encrypted Vault. */
    fun persistSwarmRoles(roles: Map<String, SavedProvider>) {
        if (!vault.isAvailable) return
        val jsonMap = roles.mapValues { (_, p) ->
            SavedProviderJson(
                preset = p.preset.name, apiKey = p.apiKey,
                endpoint = p.endpoint, model = p.model, balance = p.balance
            )
        }
        vault.store(VAULT_KEY_SWARM_ROLES,
             settingsAppJson.encodeToString(MapSerializer(String.serializer(), SavedProviderJson.serializer()), jsonMap))
    }

    companion object {
        private const val VAULT_KEY_PROVIDERS = "saved_providers_json"
        private const val VAULT_KEY_SWARM_ROLES = "swarm_roles_json"
    }
}
