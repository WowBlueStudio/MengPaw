package com.mengpaw.core.cli

/**
 * Registry that maps command names (e.g. "fs.cat") to their executors.
 */
class CommandRegistry {
    private val commands = mutableMapOf<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>()
    private val namespaces = mutableMapOf<String, MutableMap<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>>()

    /**
     * Register a command with full path like "fs.cat"
     */
    fun register(fullName: String, executor: suspend (List<String>, ExecutionContext) -> ExecutionResult) {
        commands[fullName] = executor
        val parts = fullName.split(".", limit = 2)
        if (parts.size == 2) {
            namespaces.computeIfAbsent(parts[0]) { mutableMapOf() }[parts[1]] = executor
        }
    }

    /**
     * Register all commands for a namespace at once.
     */
    fun registerNamespace(namespace: String, executors: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>) {
        executors.forEach { (name, executor) ->
            register("$namespace.$name", executor)
        }
    }

    /**
     * Find a command by its full name.
     */
    fun find(fullName: String): (suspend (List<String>, ExecutionContext) -> ExecutionResult)? {
        return commands[fullName]
    }

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
    fun unregister(fullName: String): Boolean {
        val removed = commands.remove(fullName) != null
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
    fun unregisterNamespace(namespace: String): Int {
        val nsCommands = namespaces.remove(namespace) ?: return 0
        var count = 0
        nsCommands.keys.forEach { cmdName ->
            if (commands.remove("$namespace.$cmdName") != null) count++
        }
        return count
    }

    /**
     * List all registered namespaces.
     */
    fun namespaces(): Set<String> = namespaces.keys
}
