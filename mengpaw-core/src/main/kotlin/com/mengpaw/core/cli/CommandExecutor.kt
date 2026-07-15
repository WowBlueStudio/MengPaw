package com.mengpaw.core.cli

/**
 * Represents the result of executing a CLI command.
 */
data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = if (success) 0 else 1
) {
    companion object {
        fun ok(output: String) = ExecutionResult(true, output)
        fun fail(error: String, code: Int = 1) = ExecutionResult(false, "", error, code)
    }
}

/**
 * Context carrying metadata for command execution.
 */
data class ExecutionContext(
    val sessionId: String,
    val userId: String = "agent",
    val workDir: String = "/data/data/com.mengpaw/files",
    val environment: Map<String, String> = emptyMap()
)
