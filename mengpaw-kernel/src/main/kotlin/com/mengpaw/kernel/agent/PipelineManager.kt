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

        /**
         * 框架层参数签名表 (自检报告 P0-3) — 内置命名空间的必选位置参数预校验。
         * 只收录"必选参数不足必错"的命令; 0 参合法命令 (self.search 无参=统计, agent.ls
         * 无参=工作区根, agent.memory.mid 无参=全部) 与 flag 形态命令 (plugin.verify --all)
         * 一律不注册 — 由 handler 内部自查, 框架层不误拦。
         * CliInterpreter 把 --flag 归入 flags, 故 minArgs 只数位置参数。
         */
        private val SELF_SIGNATURES = mapOf(
            "avatar" to com.mengpaw.kernel.cli.CommandSignature("self.avatar <图片路径>", 1),
            "notify.message" to com.mengpaw.kernel.cli.CommandSignature("self.notify.message <文本>", 1),
            "notify.banner" to com.mengpaw.kernel.cli.CommandSignature("self.notify.banner <文本> [--level info|success|warn|error]", 1)
        )

        private val PLUGIN_SIGNATURES = mapOf(
            "search" to com.mengpaw.kernel.cli.CommandSignature("plugin.search <关键词>", 1),
            "install" to com.mengpaw.kernel.cli.CommandSignature("plugin.install <插件ID>", 1),
            "uninstall" to com.mengpaw.kernel.cli.CommandSignature("plugin.uninstall <插件ID>", 1),
            "info" to com.mengpaw.kernel.cli.CommandSignature("plugin.info <插件ID>", 1),
            "enable" to com.mengpaw.kernel.cli.CommandSignature("plugin.enable <插件ID>", 1),
            "disable" to com.mengpaw.kernel.cli.CommandSignature("plugin.disable <插件ID>", 1),
            "update" to com.mengpaw.kernel.cli.CommandSignature("plugin.update <插件ID>", 1),
            "auto" to com.mengpaw.kernel.cli.CommandSignature("plugin.auto <wake|sleep|status|sleep-idle>", 1)
        )

        private val SECURITY_SIGNATURES = mapOf(
            "block" to com.mengpaw.kernel.cli.CommandSignature("security.block <来源>", 1),
            "unblock" to com.mengpaw.kernel.cli.CommandSignature("security.unblock <来源>", 1)
        )

        private val AGENT_SIGNATURES = mapOf(
            "read" to com.mengpaw.kernel.cli.CommandSignature("agent.read <路径>", 1),
            "write" to com.mengpaw.kernel.cli.CommandSignature("agent.write <路径> <内容>", 2),
            "rm" to com.mengpaw.kernel.cli.CommandSignature("agent.rm <路径> [--force]", 1),
            "mkdir" to com.mengpaw.kernel.cli.CommandSignature("agent.mkdir <路径>", 1),
            "session.delete" to com.mengpaw.kernel.cli.CommandSignature("agent.session.delete <id>", 1),
            "session.archive" to com.mengpaw.kernel.cli.CommandSignature("agent.session.archive <id> [--unarchive]", 1),
            "memory.record" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.record <内容>", 1),
            "memory.keep" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.keep <内容>", 1),
            "memory.read" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.read <id>", 1),
            "memory.search" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.search <关键词> [--track long|mid|project]", 1),
            "memory.write" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.write <id> <内容>", 2),
            "memory.project.save" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.project.save <项目名> <内容>", 2),
            "memory.project.delete" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.project.delete <项目名>", 1),
            "memory.mid.delete" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.mid.delete <日期>", 1),
            "memory.rm" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.rm <时间戳>", 1),
            "memory.edit" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.edit <时间戳> <内容>", 2),
            "memory.mid.rm" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.mid.rm <日期> <时间戳>", 2),
            "memory.mid.edit" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.mid.edit <日期> <时间戳> <内容>", 3),
            "memory.project.rm" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.project.rm <项目名> <时间戳>", 2),
            "memory.project.edit" to com.mengpaw.kernel.cli.CommandSignature("agent.memory.project.edit <项目名> <时间戳> <内容>", 3)
        )

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
        registry.registerNamespace("self", SelfExecutor.commands, SELF_SIGNATURES)

        // Built-in: evolution namespace (进化系统, always available)
        registry.registerNamespace("evolution", com.mengpaw.kernel.evolution.EvolutionExecutor.commands)

        // Built-in: plugin namespace (always available)
        registry.registerNamespace("plugin", pluginExecutor.commands, PLUGIN_SIGNATURES)

        // Built-in: agent namespace (always available)
        registry.registerNamespace("agent", agentExecutor.commands, AGENT_SIGNATURES)

        // Built-in: security namespace (攻击来源黑名单, always available)
        registry.registerNamespace("security", com.mengpaw.kernel.namespace.SecurityExecutor.commands, SECURITY_SIGNATURES)

        // Built-in: swarm namespace (火种模式运行时状态, v0.35.5)
        registry.registerNamespace("swarm", com.mengpaw.kernel.agent.SwarmExecutor.commands)

        // Additional namespaces (e.g. "sys" from Android adapter)
        additionalNamespaces.forEach { (ns, commands) ->
            registry.registerNamespace(ns, commands)
        }

        // Dynamic: register all active plugin commands
        pluginManager.getActivePlugins().forEach { plugin ->
            val ns = pluginNamespaceFor(plugin.metadata.id)
            plugin.commands.forEach { (name, handler) ->
                registry.register("$ns.$name", null, handler)
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
        val namespaces = mutableSetOf("self", "evolution", "agent", "plugin", "security")
        additionalNamespaces.keys.forEach { namespaces.add(it) }
        pluginManager.getActivePlugins().forEach { plugin ->
            namespaces.add(pluginNamespaceFor(plugin.metadata.id))
        }
        return namespaces.sorted()
    }
}
