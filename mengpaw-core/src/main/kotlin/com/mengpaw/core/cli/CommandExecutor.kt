// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.cli

/**
 * Standard error codes for CLI command execution.
 */
object ErrorCodes {
    const val ERR_NOT_FOUND = "ERR_NOT_FOUND"
    const val ERR_PERMISSION_DENIED = "ERR_PERMISSION_DENIED"
    const val ERR_INVALID_INPUT = "ERR_INVALID_INPUT"
    const val ERR_INTERNAL = "ERR_INTERNAL"
    const val ERR_TIMEOUT = "ERR_TIMEOUT"
    const val ERR_IO = "ERR_IO"
}

/**
 * Represents the result of executing a CLI command.
 */
data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = if (success) 0 else 1,
    val errorCode: String? = null
) {
    companion object {
        fun ok(output: String) = ExecutionResult(true, output)
        fun fail(error: String, code: Int = 1, errorCode: String? = null) =
            ExecutionResult(false, "", error, code, errorCode)
    }
}

/**
 * Context carrying metadata for command execution.
 */
data class ExecutionContext(
    val sessionId: String,
    val userId: String = "agent",
    val workDir: String = com.mengpaw.core.DataPaths.BASE,
    val environment: Map<String, String> = emptyMap(),
    val agentName: String? = null
)
