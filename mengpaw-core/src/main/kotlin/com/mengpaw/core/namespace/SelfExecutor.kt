package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult

/**
 * Self-introspection namespace - allows Agent to query its own state.
 */
object SelfExecutor {
    val commands = mapOf(
        "status" to ::status,
        "config" to ::config,
        "stats" to ::stats,
        "version" to ::version
    )

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            """
            Session: ${ctx.sessionId}
            User: ${ctx.userId}
            WorkDir: ${ctx.workDir}
            Uptime: (runtime statistics)
            Memory: (heap usage)
            """.trimIndent()
        )
    }

    private suspend fun config(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.ok(
                """
                maxSteps = 50
                timeoutMs = 300000
                screenshotEnabled = true
                browserPoolSize = 4
                """.trimIndent()
            )
        }
        return ExecutionResult.ok("Config: ${args.joinToString(" ")}")
    }

    private suspend fun stats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        return ExecutionResult.ok(
            """
            Memory: ${usedMb}MB / ${totalMb}MB
            Processors: ${runtime.availableProcessors()}
            Threads: ${Thread.activeCount()}
            """.trimIndent()
        )
    }

    private suspend fun version(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("檬爪 v0.1.0-alpha (core)")
    }
}
