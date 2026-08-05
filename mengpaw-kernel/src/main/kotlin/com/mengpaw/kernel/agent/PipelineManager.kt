// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.CommandRegistry
import com.mengpaw.kernel.cli.Pipeline
import com.mengpaw.kernel.namespace.SelfExecutor
import com.mengpaw.kernel.plugin.PluginExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.pluginNamespaceFor
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

        /** 折叠主阈值（默认档 0.9 — 稀有折叠哲学; 保守模型档见 [compactRatioFor]）。 */
        const val COMPACT_RATIO = 0.90
        /** 强制折叠线 — 超过后免 MIN_FOLD_TOKENS 收益门槛。 */
        const val COMPACT_FORCE_RATIO = 0.95
        const val MIN_FOLD_TOKENS = 400

        /** 保守模型名单 — 旧/小模型有效窗口短，折叠阈值回落 0.8 防实际降智区工作。 */
        private val conservativeModelRegex = Regex(
            "(^|[^a-z0-9])(flash-mini|nano|lite|mini|7b|8b|13b)([^a-z0-9]|$)"
        )

        /**
         * 按模型名解析折叠主阈值 — kernel 与壳层共用的单一规则（消灭双份硬编码漂移）。
         * 默认 0.90（Claude Code 稀有折叠哲学）; 保守模型 0.80（保持旧阶梯）。
         * P2 修复: 词边界匹配（"mini" 不误伤 minimax / litemode 等含子串的模型名）。
         */
        fun compactRatioFor(modelName: String): Double {
            val name = modelName.lowercase()
            return if (conservativeModelRegex.containsMatchIn(name)) 0.80 else 0.90
        }
    }

    /** Integrity provider for path-level file protection; set after construction for Android. */
    var integrityProvider: IntegrityProvider = NoOpIntegrityProvider

    /**
     * Cached pipeline, rebuilt only when plugins change (via [invalidatePipeline]).
     * Thread-safe via @Volatile.
     */
    @Volatile private var cachedPipeline: Pipeline? = null

    /** buildPipeline double-checked 锁 — 防并行首次构建分片（限流器/缓存分片 + registry 指针抖动）。 */
    private val pipelineBuildLock = Any()

    /** 本管理器构建的 registry — listCommands 直接读本引擎（防多 Agent 全局指针串扰）。 */
    @Volatile
    private var registry: CommandRegistry? = null

    /** Invalidate cached pipeline when plugins change. Call after plugin install/uninstall. */
    fun invalidatePipeline() { cachedPipeline = null }

    /**
     * Build (or return cached) execution pipeline.
     * Registers all namespaces: built-in (self, plugin, agent), additional, and dynamic plugins.
     * P2 修复: double-checked 加锁 — 首个多 Action 批/mission 并行 worker 同时 miss 时
     * 会各建 Pipeline（限流器/缓存分片 + 全局 registry 指针抖动）。
     */
    fun buildPipeline(): Pipeline {
        cachedPipeline?.let { return it }
        synchronized(pipelineBuildLock) {
            cachedPipeline?.let { return it }
            return buildPipelineUnlocked()
        }
    }

    private fun buildPipelineUnlocked(): Pipeline {
        val registry = CommandRegistry().also { this.registry = it }

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
            val ns = pluginNamespaceFor(plugin.metadata.id)
            plugin.commands.forEach { (name, handler) ->
                registry.register("$ns.$name", handler)
            }
        }

        pluginManager.bindRegistry(registry)
        val pipeline = Pipeline(registry = registry, resultCache = com.mengpaw.kernel.cli.CommandResultCache())
        pipeline.integrityProvider = integrityProvider
        cachedPipeline = pipeline
        return pipeline
    }

    /** 本引擎注册表的命令名列表（! 命令补全用 — 读本引擎 registry, 不依赖全局指针）。 */
    fun listCommands(): List<String> = registry?.list() ?: emptyList()

    /** List all active CLI namespaces (built-in + plugins) for settings display. */
    fun getActiveNamespaces(): List<String> {
        val namespaces = mutableSetOf("self", "evolution", "agent", "plugin")
        additionalNamespaces.keys.forEach { namespaces.add(it) }
        pluginManager.getActivePlugins().forEach { plugin ->
            namespaces.add(pluginNamespaceFor(plugin.metadata.id))
        }
        return namespaces.sorted()
    }
}
