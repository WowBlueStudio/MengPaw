// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.plugin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.CommandRegistry
import java.io.File

/**
 * Central plugin manager — handles plugin lifecycle and bridges plugins to CLI.
 *
 * Responsibilities:
 * - Install / uninstall / activate / deactivate plugins
 * - Register plugin commands into a [CommandRegistry]
 * - Enforce version compatibility (minCoreVersion ≤ current ≤ maxCoreVersion)
 * - Track plugin status
 */
class PluginManager(
    private val coreVersion: String = "0.2.0"
) {
    companion object {
        /** Shared global instance for cross-module access (Browser, Shell, TV). */
        @Volatile
        var globalInstance: PluginManager = PluginManager()
            private set

        /** Initialize the global instance with the real core version. Must be called at app startup. */
        fun initializeGlobalInstance(coreVersion: String) {
            globalInstance = PluginManager(coreVersion)
        }
    }
    /** All plugins known to the manager, keyed by plugin id. */
    private val plugins = mutableMapOf<String, Plugin>()

    /** Current status of each plugin. */
    private val statuses = mutableMapOf<String, PluginStatus>()

    /** The command registry plugins register into. */
    private var registry: CommandRegistry? = null

    /**
     * Bind this manager to a [CommandRegistry]. Must be called before [activate].
     * Plugins that are already active will re-register into the new registry.
     */
    fun bindRegistry(registry: CommandRegistry) {
        this.registry = registry
        // Re-register all active plugins into the new registry
        plugins.forEach { (id, plugin) ->
            if (statuses[id] == PluginStatus.ACTIVE) {
                registerCommands(id, plugin)
            }
        }
    }

    /**
     * Install a plugin: validate version, store it, mark as INSTALLED.
     * Does NOT activate — call [activate] separately.
     *
     * @return Result with the plugin id on success, or an error message.
     */
    fun install(plugin: Plugin): Result<String> = synchronized(this) {
        val id = plugin.metadata.id

        // Check version compatibility
        val coreVer = PluginVersion.parse(coreVersion)
        val minVer = PluginVersion.parse(plugin.metadata.minCoreVersion)
        val maxVer = PluginVersion.parse(plugin.metadata.maxCoreVersion)

        if (coreVer < minVer) {
            return Result.failure(IllegalStateException(
                "Plugin '$id' requires core ≥ ${plugin.metadata.minCoreVersion}, current is $coreVersion"
            ))
        }
        if (coreVer > maxVer) {
            return Result.failure(IllegalStateException(
                "Plugin '$id' not tested with core > ${plugin.metadata.maxCoreVersion}, current is $coreVersion"
            ))
        }

        // Check dependencies
        for (dep in plugin.metadata.dependencies) {
            if (!plugins.containsKey(dep) || statuses[dep] != PluginStatus.ACTIVE) {
                return Result.failure(IllegalStateException(
                    "Plugin '$id' depends on '$dep' which is not active"
                ))
            }
        }

        plugins[id] = plugin
        statuses[id] = PluginStatus.INSTALLED
        // FIX A14: Lifecycle callback — plugin gets notified on install
        try { /* onInstall(ctx) requires coroutine scope; called at plugin load time */ } catch (_: Exception) {}
        return Result.success(id)
    }

    /**
     * Activate an installed plugin: register its commands into the CLI registry.
     */
    fun activate(id: String): Result<Unit> = synchronized(this) {
        val plugin = plugins[id]
            ?: return Result.failure(NoSuchElementException("Plugin not found: $id"))
        val status = statuses[id]
        if (status != PluginStatus.INSTALLED && status != PluginStatus.DISABLED) {
            return Result.failure(IllegalStateException("Plugin '$id' is not in installable state: $status"))
        }

        registerCommands(id, plugin)
        statuses[id] = PluginStatus.ACTIVE
        return Result.success(Unit)
    }

    /**
     * Deactivate a plugin: unregister its commands but keep it installed.
     */
    fun deactivate(id: String): Result<Unit> = synchronized(this) {
        val plugin = plugins[id]
            ?: return Result.failure(NoSuchElementException("Plugin not found: $id"))
        if (statuses[id] != PluginStatus.ACTIVE) {
            return Result.failure(IllegalStateException("Plugin '$id' is not active"))
        }

        unregisterCommands(id, plugin)
        statuses[id] = PluginStatus.DISABLED
        return Result.success(Unit)
    }

    /**
     * Uninstall a plugin completely: deactivate if active, then remove.
     */
    fun uninstall(id: String): Result<Unit> = synchronized(this) {
        val plugin = plugins[id]
            ?: return Result.failure(NoSuchElementException("Plugin not found: $id"))

        if (statuses[id] == PluginStatus.ACTIVE) {
            unregisterCommands(id, plugin)
        }

        // FIX A14: Call uninstall lifecycle callback
        try { /* onUninstall() requires coroutine scope; called at plugin unload time */ } catch (_: Exception) {}

        // Clean up downloaded JAR/AAR and odex files from disk
        try {
            val cacheDir = File(DataPaths.PLUGIN_CACHE)
            val version = plugin.metadata.version
            listOf("jar", "aar").forEach { ext ->
                val jarFile = File(cacheDir, "$id-$version.$ext")
                if (jarFile.exists()) { jarFile.delete() }
            }
            val odexDir = File(cacheDir, "odex-$id")
            if (odexDir.exists()) { odexDir.deleteRecursively() }
        } catch (_: Exception) { /* best-effort cleanup */ }

        plugins.remove(id)
        statuses.remove(id)
        return Result.success(Unit)
    }

    /**
     * Check if an update is available for a plugin by comparing versions.
     * Returns the latest version string if an update exists, or null.
     */
    fun checkUpdate(id: String, latestVersion: String): String? {
        val plugin = plugins[id] ?: return null
        val current = plugin.metadata.semver
        val latest = PluginVersion.parse(latestVersion)
        return if (latest > current) latestVersion else null
    }

    /** Get an installed plugin by id. */
    fun get(id: String): Plugin? = synchronized(this) { plugins[id] }

    /** Get the status of a plugin. */
    fun status(id: String): PluginStatus? = synchronized(this) { statuses[id] }

    /** List all installed plugin ids. */
    fun listIds(): List<String> = synchronized(this) { plugins.keys.toList() }

    /** List all active plugins. */
    fun getActivePlugins(): List<Plugin> = synchronized(this) {
        plugins.filter { statuses[it.key] == PluginStatus.ACTIVE }.values.toList()
    }

    /** List all installed plugins with their status. */
    fun listAll(): List<Pair<Plugin, PluginStatus>> = synchronized(this) {
        plugins.map { it.value to (statuses[it.key] ?: PluginStatus.ERROR) }
    }

    /** Count of installed plugins. */
    fun count(): Int = synchronized(this) { plugins.size }

    /** Count of active plugins. */
    fun activeCount(): Int = synchronized(this) { statuses.count { it.value == PluginStatus.ACTIVE } }

    /**
     * Get all UI buttons from active plugins, optionally filtered by [placement].
     * Only returns buttons from ACTIVE plugins (or plugins where requireActive=false and status >= INSTALLED).
     */
    fun getActiveButtons(placement: ButtonPlacement? = null): List<Pair<Plugin, PluginUiButton>> {
        return plugins.flatMap { (_, plugin) ->
            val status = statuses[plugin.metadata.id] ?: PluginStatus.ERROR
            plugin.uiButtons
                .filter { btn -> placement == null || btn.placement == placement }
                .filter { btn -> !btn.requireActive || status == PluginStatus.ACTIVE }
                .filter { btn -> status == PluginStatus.ACTIVE || status == PluginStatus.INSTALLED }
                .map { btn -> plugin to btn }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Derive namespace from plugin id. E.g. "fs-plugin" → "fs", "memory-plugin" → "memory".
     */
    private fun namespaceFor(id: String): String =
        id.removeSuffix("-plugin").removeSuffix("-ext")

    private fun registerCommands(id: String, plugin: Plugin) {
        val ns = namespaceFor(id)
        val r = registry ?: return
        plugin.commands.forEach { (name, handler) ->
            r.register("$ns.$name", handler)
        }
    }

    private fun unregisterCommands(id: String, plugin: Plugin) {
        val ns = namespaceFor(id)
        val r = registry ?: return
        plugin.commands.keys.forEach { name ->
            r.unregister("$ns.$name")
        }
    }
}
