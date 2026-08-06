// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.MengPawVersion
import com.mengpaw.kernel.cli.CommandRegistry
import java.io.File

/**
 * 插件 id → CLI 命名空间 — 全内核唯一权威推导。
 * 所有 removeSuffix 特例收敛于此, 注册/搜索索引/CLI.md/MCP 桥显示名天然一致。
 * 特例来源 (插件源码命令键):
 * - browser-mcp-plugin 命令键自带 "mcp." 前缀 → ns 取 "browser" 拼出 browser.mcp.*
 * - browser-search-plugin 命令键为短名 → ns 取 "search" 拼出 search.clean/md/...
 * - memory-twin-plugin 命令键为短名 → ns 取 "twin"
 */
fun pluginNamespaceFor(id: String): String {
    val base = id.removeSuffix("-plugin").removeSuffix("-ext")
    if (base.startsWith("memory-")) return base.removePrefix("memory-")
    return when (base) {
        "browser-mcp" -> "browser"
        "browser-search" -> "search"
        else -> base
    }
}

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
    // 默认取真实内核版本 (gradle 生成的 MengPawVersion) — 此前硬编码 "0.2.0" 使
    // minCoreVersion/maxCoreVersion 门禁在未显式传参时完全失效 (任何插件都能通过)
    private val coreVersion: String = MengPawVersion.FRAMEWORK
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
    suspend fun install(plugin: Plugin): Result<String> {
        val id = plugin.metadata.id

        // Version checks and dependency validation (synchronized — uses shared state)
        synchronized(this) {
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

            // Cyclic dependency detection
            checkCyclicDeps(id, plugin.metadata.dependencies)

            // Port conflict detection — 插件声明端口与已安装/激活插件冲突时拒绝安装
            val declaredPorts = plugin.metadata.ports.filter { it in 1..65535 }
            if (declaredPorts.isNotEmpty()) {
                val conflict = plugins.entries.firstOrNull { (_, other) ->
                    other.metadata.ports.filter { it in 1..65535 }.any { it in declaredPorts }
                }
                if (conflict != null) {
                    val clash = declaredPorts.first { it in conflict.value.metadata.ports }
                    return Result.failure(IllegalStateException(
                        "Plugin '$id' declares port $clash, already occupied by '${conflict.key}'"
                    ))
                }
            }

            plugins[id] = plugin
            statuses[id] = PluginStatus.INSTALLED
        }

        // Lifecycle callback — called outside synchronized block (suspend function)
        try { plugin.onInstall(DefaultPluginContext(id, coreVersion)) } catch (e: Exception) {
            KernelLog.w("PluginManager", "onInstall failed for $id: ${e.message}")
        }
        return Result.success(id)
    }

    /**
     * Activate an installed plugin: register its commands into the CLI registry.
     *
     * 生命周期对称: 禁用 (DISABLED) 后重新激活时先重跑 onInstall —
     * 对应 deactivate 已调用 onUninstall, 提供者型插件 (dream/evolution/framework) 依赖
     * onInstall 恢复提供者注册与网关。首装路径 (install → activate) 不重复 onInstall。
     * 回调在锁外执行 (suspend function), 与 install/uninstall 同模式。
     */
    suspend fun activate(id: String): Result<Unit> {
        val needsOnInstall: Boolean
        val plugin = synchronized(this) {
            val p = plugins[id]
                ?: return Result.failure(NoSuchElementException("Plugin not found: $id"))
            val status = statuses[id]
            if (status != PluginStatus.INSTALLED && status != PluginStatus.DISABLED) {
                return Result.failure(IllegalStateException("Plugin '$id' is not in installable state: $status"))
            }
            needsOnInstall = (status == PluginStatus.DISABLED)
            p
        }
        if (needsOnInstall) {
            try { plugin.onInstall(DefaultPluginContext(id, coreVersion)) } catch (e: Exception) {
                KernelLog.w("PluginManager", "onInstall failed for $id: ${e.message}")
            }
        }
        return synchronized(this) {
            registerCommands(id, plugin)
            statuses[id] = PluginStatus.ACTIVE
            // 联动命令搜索索引: 插件激活后注册其命令到 BM25 索引
            registerSearchIndex(id, plugin)
            Result.success(Unit)
        }
    }

    /**
     * Deactivate a plugin: unregister its commands but keep it installed.
     *
     * 生命周期对称: 禁用时调用 onUninstall — 提供者型插件 (dream/evolution/framework)
     * 的提供者注册与网关 (MCP/发现) 随禁用停用, 重新启用时 activate 重跑 onInstall 恢复。
     */
    suspend fun deactivate(id: String): Result<Unit> {
        val plugin = synchronized(this) {
            val p = plugins[id]
                ?: return Result.failure(NoSuchElementException("Plugin not found: $id"))
            if (statuses[id] != PluginStatus.ACTIVE) {
                return Result.failure(IllegalStateException("Plugin '$id' is not active"))
            }
            unregisterCommands(id, p)
            p
        }
        try { plugin.onUninstall() } catch (e: Exception) {
            KernelLog.w("PluginManager", "onUninstall failed for $id: ${e.message}")
        }
        return synchronized(this) {
            statuses[id] = PluginStatus.DISABLED
            Result.success(Unit)
        }
    }

    /**
     * Uninstall a plugin completely: deactivate if active, then remove.
     */
    suspend fun uninstall(id: String): Result<Unit> {
        // Lifecycle callback — called outside synchronized block (suspend function)
        val plugin = synchronized(this) {
            val p = plugins[id]
                ?: return Result.failure(NoSuchElementException("Plugin not found: $id"))
            if (statuses[id] == PluginStatus.ACTIVE) {
                unregisterCommands(id, p)
            }
            p
        }
        try { plugin.onUninstall() } catch (e: Exception) {
            KernelLog.w("PluginManager", "onUninstall failed for $id: ${e.message}")
        }
        // 联动命令搜索索引: 卸载时移除该插件的所有命令
        com.mengpaw.kernel.cli.CommandSearch.removeByNamespace(namespaceFor(id))

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
        synchronized(this) {
            return plugins.flatMap { (_, plugin) ->
                val status = statuses[plugin.metadata.id] ?: PluginStatus.ERROR
                plugin.uiButtons
                    .filter { btn -> placement == null || btn.placement == placement }
                    .filter { btn -> !btn.requireActive || status == PluginStatus.ACTIVE }
                    .filter { btn -> status == PluginStatus.ACTIVE || status == PluginStatus.INSTALLED }
                    .map { btn -> plugin to btn }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Derive namespace from plugin id. E.g. "fs-plugin" → "fs",
     * "memory-twin-plugin" → "twin". (memory-plugin 已融入内核 agent.memory.*)
     * 委托 [pluginNamespaceFor] 权威推导 (browser-mcp/browser-search 特例在共享函数内)。
     */
    private fun namespaceFor(id: String): String = pluginNamespaceFor(id)

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

    /** 将插件命令注册到 BM25 命令搜索索引, 使 Agent 能通过 self.search 发现.
     *  优先读取插件的自定义关键词, 无定义时自动生成基础关键词. */
    private fun registerSearchIndex(id: String, plugin: Plugin) {
        val ns = namespaceFor(id)
        val name = plugin.metadata.name
        val desc = plugin.metadata.description
        val kws = plugin.metadata.commandKeywords

        plugin.commands.keys.forEach { cmdName ->
            val fullName = "$ns.$cmdName"
            val entry = kws[cmdName]
            // 有自定义关键词 → 用自定义的; 无 → 兜底用命名空间/命令名/插件名
            val zh = if (entry != null && entry.zh.isNotEmpty()) entry.zh
                     else listOf(ns, cmdName, name)
            val en = if (entry != null && entry.en.isNotEmpty()) entry.en
                     else listOf(ns, cmdName, name)
            val displayDesc = if (entry != null) desc else "[$name] $desc"
            try {
                // registerOrUpdate: 重新激活时允许更新关键词 (用户可能改进了同义词表)
                com.mengpaw.kernel.cli.CommandSearch.registerOrUpdate(
                    com.mengpaw.kernel.cli.CommandIndex(
                        fullName = fullName, namespace = ns,
                        description = displayDesc,
                        usage = fullName,
                        zhKeywords = zh, enKeywords = en
                    )
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Recursively detect cyclic dependencies starting from [id].
     * Throws [IllegalStateException] if a cycle or self-reference is found.
     */
    private fun checkCyclicDeps(id: String, deps: List<String>, visited: MutableSet<String> = mutableSetOf()) {
        visited.add(id)
        for (dep in deps) {
            if (dep == id) throw IllegalStateException("Plugin $id depends on itself")
            if (dep in visited) throw IllegalStateException("Cyclic dependency detected: $id -> $dep")
            val depPlugin = plugins[dep]
            if (depPlugin != null && statuses[dep] == PluginStatus.ACTIVE) {
                checkCyclicDeps(dep, depPlugin.metadata.dependencies, visited)
            }
        }
    }

    /**
     * Simple PluginContext implementation for lifecycle callbacks.
     */
    private class DefaultPluginContext(private val pluginId: String, private val coreVer: String) : PluginContext {
        override val storageDir: String = DataPaths.pluginDir(pluginId)
        override val coreVersion: String = coreVer
        override fun log(message: String) { KernelLog.i("Plugin/$pluginId", message) }
        override val commandExecutor: com.mengpaw.kernel.cli.CommandExecutor =
            com.mengpaw.kernel.cli.DefaultCommandExecutor()
    }
}
