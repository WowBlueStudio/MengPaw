// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.plugin.PluginType

// ── 插件 UI 数据模型 — 拆自 PluginViewModel.kt (2026-08-06, >400 行文件拆分批次4) ──

/**
 * Install progress state for UI rendering.
 */
sealed class InstallState {
    data object Idle : InstallState()
    data class Downloading(val progress: Float) : InstallState()
    data object Verifying : InstallState()
    data class Installing(val step: String) : InstallState()
    data class Done(val pluginId: String) : InstallState()
    data class Failed(val error: String) : InstallState()
}

/**
 * Plugin suggestion triggered when Agent tries to use an uninstalled command.
 */
data class PluginSuggestion(
    val namespace: String,
    val pluginId: String,
    val pluginName: String,
    val description: String,
    val missingCommand: String
)

/**
 * Whether a plugin can be installed from the marketplace.
 */
enum class PluginAvailability {
    /** Already compiled into the APK — no download needed. */
    BUILTIN,
    /** Available for download from the marketplace. */
    DOWNLOADABLE,
    /** Listed but not yet released. */
    UNAVAILABLE,
    /** 已嵌入引擎 — 内置功能，不可卸载 */
    EMBEDDED
}

/**
 * UI-ready plugin item combining marketplace info with install status.
 */
data class PluginUiItem(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val type: PluginType,
    val author: String,
    val permissions: List<String>,
    val commands: List<String>,
    val isInstalled: Boolean,
    val isActive: Boolean,
    val installState: InstallState = InstallState.Idle,
    val availability: PluginAvailability = PluginAvailability.BUILTIN,
    /** 插件英文名 — 显示为「中文名 (English)」; null 时只显示 name。 */
    val enName: String? = null
) {
    /** UI 显示名 — 插件统一「中文名 (English)」中英对照格式。 */
    val displayName: String get() = enName?.let { "$name ($it)" } ?: name
}
