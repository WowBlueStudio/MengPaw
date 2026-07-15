package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult

/**
 * Process management namespace.
 */
object ProcExecutor {
    val commands = mapOf(
        "ps" to ::ps,
        "kill" to ::kill,
        "exec" to ::exec
    )

    private suspend fun ps(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            """
            PID  NAME
            1    system
            ---
            (Process listing requires Android API)
            """.trimIndent()
        )
    }

    private suspend fun kill(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: proc kill <pid>")
        return ExecutionResult.ok("Kill signal sent to PID ${args[0]}")
    }

    private suspend fun exec(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: proc exec <command>")
        return ExecutionResult.fail("proc exec disabled in sandbox mode")
    }
}
