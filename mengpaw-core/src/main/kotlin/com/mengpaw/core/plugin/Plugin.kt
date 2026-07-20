// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.plugin

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult

/**
 * Plugin type determines how the plugin is distributed and executed.
 */
enum class PluginType {
    /** Compiled AAR/JAR — full Kotlin/Android capability, distributed as binary. */
    NATIVE,
    /** JSON manifest + Kotlin script — lightweight, Agent-creatable. */
    SCRIPT
}

/**
 * Plugin metadata describing identity, version, capabilities, and requirements.
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val type: PluginType = PluginType.NATIVE,
    val author: String = "",
    val description: String = "",
    val permissions: List<String> = emptyList(),
    val minCoreVersion: String = "0.1.0",
    val maxCoreVersion: String = "99.99.99",
    val dependencies: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val downloadUrl: String = "",
    val checksum: String = "",
    val sizeBytes: Long = 0
) {
    /** Semantic version as [PluginVersion] for comparison. */
    val semver: PluginVersion get() = PluginVersion.parse(version)
}

/**
 * Command handler signature — identical to namespace executor commands.
 * (List<String> args, ExecutionContext ctx) -> ExecutionResult
 */
typealias CommandHandler = suspend (List<String>, ExecutionContext) -> ExecutionResult

/**
 * Plugin context passed to plugins during lifecycle callbacks.
 * Provides access to core services and the plugin's own storage.
 */
interface PluginContext {
    /** Absolute path to the plugin's private storage directory. */
    val storageDir: String
    /** The core version currently running. */
    val coreVersion: String
    /** Log a message visible in Agent execution logs. */
    fun log(message: String)
}

/**
 * Where a plugin's UI button should appear in the MengPaw interface.
 */
enum class ButtonPlacement {
    /** MainScreen bottom expand sheet (the + button tray). */
    BOTTOM_SHEET,
    /** MainScreen header bar (next to new-session / history buttons). */
    HEADER_BAR,
    /** BrowserActivity dropdown overflow menu. */
    BROWSER_MENU,
    /** Browser toolbar (next to back/forward/refresh). */
    BROWSER_TOOLBAR,
    /** Settings screen — appears as a section card. */
    SETTINGS_SECTION
}

/**
 * A UI button contributed by a plugin. Plugins declare these in their metadata,
 * and the UI framework creates/hides them automatically based on plugin status.
 */
data class PluginUiButton(
    /** Unique ID within this plugin (e.g. "search", "summarize", "config"). */
    val id: String,
    /** Display label. */
    val label: String,
    /** Material icon name (e.g. "Search", "Image", "Star"). */
    val iconName: String = "Extension",
    /** Where this button appears in the UI. */
    val placement: ButtonPlacement = ButtonPlacement.BOTTOM_SHEET,
    /** CLI command to execute when clicked (e.g. "tavily.search <query>"). */
    val command: String = "",
    /** Only show when the plugin is ACTIVE (true) or also when disabled/installed (false). */
    val requireActive: Boolean = true
)

/**
 * Unified plugin interface — the single contract every MengPaw plugin implements.
 *
 * A plugin contributes CLI commands, can declare UI buttons, and can react to lifecycle events.
 */
interface Plugin {
    /** Immutable metadata describing this plugin. */
    val metadata: PluginMetadata

    /** CLI commands contributed by this plugin, keyed by command name (e.g. "cat"). */
    val commands: Map<String, CommandHandler>

    /** UI buttons contributed by this plugin. The framework shows/hides them based on plugin status. */
    val uiButtons: List<PluginUiButton> get() = emptyList()

    /** Called after the plugin is downloaded and before it is activated. */
    suspend fun onInstall(ctx: PluginContext) {}

    /** Called when the plugin is about to be removed. Clean up resources. */
    suspend fun onUninstall() {}

    /** Called when upgrading from a previous version. */
    suspend fun onUpgrade(fromVersion: String) {}
}

/**
 * Simple semantic version for compatibility checks.
 */
data class PluginVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PluginVersion> {
    companion object {
        fun parse(v: String): PluginVersion {
            val parts = v.split(".").map { it.toIntOrNull() ?: 0 }
            return PluginVersion(
                parts.getOrElse(0) { 0 },
                parts.getOrElse(1) { 0 },
                parts.getOrElse(2) { 0 }
            )
        }
    }

    override fun compareTo(other: PluginVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Plugin status within the lifecycle.
 */
enum class PluginStatus {
    /** Downloaded but not yet installed. */
    DOWNLOADED,
    /** Installed but not yet activated (commands not registered). */
    INSTALLED,
    /** Active — commands are registered and callable. */
    ACTIVE,
    /** Disabled by user/Agent — installed but commands not available. */
    DISABLED,
    /** Installation or activation failed. */
    ERROR
}
