// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.CommandRegistry
import com.mengpaw.kernel.cli.Pipeline
import com.mengpaw.kernel.namespace.SelfExecutor
import com.mengpaw.kernel.plugin.PluginExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.security.IntegrityProvider
import com.mengpaw.kernel.security.NoOpIntegrityProvider

/**
 * Manages the CLI execution pipeline lifecycle: construction, caching,
 * namespace registration, and invalidation.
 */
class PipelineManager(
    private val pluginManager: PluginManager,
    private val pluginExecutor: PluginExecutor,
    private val agentExecutor: com.mengpaw.kernel.agent.AgentExecutor,
    private val additionalNamespaces: Map<String, Map<String, suspend (List<String>, com.mengpaw.kernel.cli.ExecutionContext) -> com.mengpaw.kernel.cli.ExecutionResult>> = emptyMap()
) {
    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 131_072
        const val SOFT_COMPACT_RATIO = 0.50
        const val TOOL_SNIP_RATIO = 0.60
        const val COMPACT_RATIO = 0.80
        const val COMPACT_FORCE_RATIO = 0.90
        const val MIN_FOLD_TOKENS = 400
    }

    /** Integrity provider for path-level file protection; set after construction for Android. */
    var integrityProvider: IntegrityProvider = NoOpIntegrityProvider

    /**
     * Cached pipeline, rebuilt only when plugins change (via [invalidatePipeline]).
     * Thread-safe via @Volatile.
     */
    @Volatile private var cachedPipeline: Pipeline? = null

    /** Invalidate cached pipeline when plugins change. Call after plugin install/uninstall. */
    fun invalidatePipeline() { cachedPipeline = null }

    /**
     * Build (or return cached) execution pipeline.
     * Registers all namespaces: built-in (self, plugin, agent), additional, and dynamic plugins.
     */
    fun buildPipeline(): Pipeline {
        cachedPipeline?.let { return it }
        val registry = CommandRegistry()

        // Expose registry for self.tools command
        SelfExecutor.commandRegistry = registry

        // Built-in: self namespace (always available)
        registry.registerNamespace("self", SelfExecutor.commands)

        // Built-in: evolution namespace (进化系统, always available)
        registry.registerNamespace("evolution", com.mengpaw.kernel.evolution.EvolutionExecutor.commands)

        // Built-in: plugin namespace (always available)
        registry.registerNamespace("plugin", pluginExecutor.commands)

        // Built-in: agent namespace (always available)
        registry.registerNamespace("agent", agentExecutor.commands)

        // Additional namespaces (e.g. "sys" from Android adapter)
        additionalNamespaces.forEach { (ns, commands) ->
            registry.registerNamespace(ns, commands)
        }

        // Dynamic: register all active plugin commands
        pluginManager.getActivePlugins().forEach { plugin ->
            val ns = plugin.metadata.id.removeSuffix("-plugin").removeSuffix("-ext")
            plugin.commands.forEach { (name, handler) ->
                registry.register("$ns.$name", handler)
            }
        }

        pluginManager.bindRegistry(registry)
        val pipeline = Pipeline(registry = registry)
        pipeline.integrityProvider = integrityProvider
        cachedPipeline = pipeline
        return pipeline
    }

    /** List all active CLI namespaces (built-in + plugins) for settings display. */
    fun getActiveNamespaces(): List<String> {
        val namespaces = mutableSetOf("self", "evolution", "agent", "plugin")
        additionalNamespaces.keys.forEach { namespaces.add(it) }
        pluginManager.getActivePlugins().forEach { plugin ->
            val ns = plugin.metadata.id.removeSuffix("-plugin").removeSuffix("-ext")
            namespaces.add(ns)
        }
        return namespaces.sorted()
    }
}
