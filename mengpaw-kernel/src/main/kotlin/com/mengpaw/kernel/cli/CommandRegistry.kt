// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

/**
 * 命令参数签名 — 框架层预校验 (自检报告 P0-3: 参数无 schema 校验, 错误反馈闭环弱)。
 * [usage] 是模型可读的期望用法; [minArgs] 是必选位置参数数 (CliInterpreter 把 --flag
 * 归入 flags, 故此处只数位置参数; flag 形态命令如 plugin.verify --all 不注册签名)。
 */
data class CommandSignature(val usage: String, val minArgs: Int = 0)

/**
 * Registry that maps command names (e.g. "fs.cat") to their executors.
 */
class CommandRegistry {
    // 并发安全: find() 是 Agent 高频路径, 注册/卸载来自插件生命周期线程 —
    // 底层 ConcurrentHashMap (computeIfAbsent 原子发布内层 map), 读写并发不挂死
    private val commands = java.util.concurrent.ConcurrentHashMap<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>()
    private val namespaces = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>>()
    // 签名表: 与命令同生命周期, 注册/卸载同步维护
    private val signatures = java.util.concurrent.ConcurrentHashMap<String, CommandSignature>()

    /**
     * Register a command with full path like "fs.cat"
     * Synchronized: called from plugin lifecycle and AgentEngine threads.
     * @param signature 可选参数签名 — 注册后 Pipeline 在调用前做必选参数预校验。
     * 注意: 放在 executor 之前是为了保住尾 lambda 调用语法 (register("x") { args, ctx -> ... })。
     */
    @Synchronized
    fun register(
        fullName: String,
        signature: CommandSignature? = null,
        executor: suspend (List<String>, ExecutionContext) -> ExecutionResult
    ) {
        commands[fullName] = executor
        if (signature != null) signatures[fullName] = signature else signatures.remove(fullName)
        val parts = fullName.split(".", limit = 2)
        if (parts.size == 2) {
            namespaces.computeIfAbsent(parts[0]) { java.util.concurrent.ConcurrentHashMap() }[parts[1]] = executor
        }
    }

    /**
     * Register all commands for a namespace at once.
     * @param signatures 短名 → 签名表; 未列出的命令不校验
     */
    @Synchronized
    fun registerNamespace(
        namespace: String,
        executors: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>,
        signatures: Map<String, CommandSignature> = emptyMap()
    ) {
        executors.forEach { (name, executor) ->
            register("$namespace.$name", signatures[name], executor)
        }
    }

    /**
     * 框架层参数预校验 — 必选位置参数不足时返回统一错误文本, 否则 null。
     * 无签名命令恒放行 (插件命令/sys.* 等不声明签名的由 handler 自查)。
     * 统一格式: 期望 <usage>, 收到 N 个参数 — 模型据此收敛重试, 不再盲猜。
     */
    fun validateArgs(fullName: String, args: List<String>): String? {
        val sig = signatures[fullName] ?: return null
        if (args.size >= sig.minArgs) return null
        return "参数错误: 期望用法「${sig.usage}」，收到 ${args.size} 个参数。请按期望用法重试。"
    }

    /**
     * Find a command by its full name. Read-only — backed by ConcurrentHashMap,
     * safe to call from AgentEngine threads while plugins install/uninstall concurrently.
     */
    fun find(fullName: String): (suspend (List<String>, ExecutionContext) -> ExecutionResult)? {
        return commands[fullName]
    }

    /** 命令是否已注册（搜索索引可用性过滤用 — 静态种子索引命中但执行器不存在的命令不外泄）。 */
    fun has(fullName: String): Boolean = commands.containsKey(fullName)

    /**
     * List all registered commands, optionally filtered by namespace.
     */
    fun list(namespace: String? = null): List<String> {
        return if (namespace != null) {
            namespaces[namespace]?.keys?.map { "$namespace.$it" } ?: emptyList()
        } else {
            commands.keys.toList()
        }
    }

    /**
     * Unregister a single command by its full name.
     * @return true if a command was removed, false if it didn't exist.
     */
    @Synchronized
    fun unregister(fullName: String): Boolean {
        val removed = commands.remove(fullName) != null
        signatures.remove(fullName)
        val parts = fullName.split(".", limit = 2)
        if (parts.size == 2) {
            namespaces[parts[0]]?.remove(parts[1])
            if (namespaces[parts[0]]?.isEmpty() == true) {
                namespaces.remove(parts[0])
            }
        }
        return removed
    }

    /**
     * Unregister all commands belonging to a namespace.
     * @return the number of commands removed.
     */
    @Synchronized
    fun unregisterNamespace(namespace: String): Int {
        val nsCommands = namespaces.remove(namespace) ?: return 0
        var count = 0
        nsCommands.keys.forEach { cmdName ->
            if (commands.remove("$namespace.$cmdName") != null) count++
            signatures.remove("$namespace.$cmdName")
        }
        return count
    }

    /**
     * List all registered namespaces.
     */
    fun namespaces(): Set<String> = namespaces.keys
}
