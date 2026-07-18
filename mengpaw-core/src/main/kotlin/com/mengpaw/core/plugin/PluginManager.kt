package com.mengpaw.core.plugin

import com.mengpaw.core.cli.CommandRegistry

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
    fun install(plugin: Plugin): Result<String> {
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
        return Result.success(id)
    }

    /**
     * Activate an installed plugin: register its commands into the CLI registry.
     */
    fun activate(id: String): Result<Unit> {
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
    fun deactivate(id: String): Result<Unit> {
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
    fun uninstall(id: String): Result<Unit> {
        val plugin = plugins[id]
            ?: return Result.failure(NoSuchElementException("Plugin not found: $id"))

        if (statuses[id] == PluginStatus.ACTIVE) {
            unregisterCommands(id, plugin)
        }

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
    fun get(id: String): Plugin? = plugins[id]

    /** Get the status of a plugin. */
    fun status(id: String): PluginStatus? = statuses[id]

    /** List all installed plugin ids. */
    fun listIds(): List<String> = plugins.keys.toList()

    /** List all active plugins. */
    fun getActivePlugins(): List<Plugin> =
        plugins.filter { statuses[it.key] == PluginStatus.ACTIVE }.values.toList()

    /** List all installed plugins with their status. */
    fun listAll(): List<Pair<Plugin, PluginStatus>> =
        plugins.map { it.value to (statuses[it.key] ?: PluginStatus.ERROR) }

    /** Count of installed plugins. */
    fun count(): Int = plugins.size

    /** Count of active plugins. */
    fun activeCount(): Int = statuses.count { it.value == PluginStatus.ACTIVE }

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
