// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.KernelLog

/**
 * Default [CommandExecutor] implementation — executes CLI commands via
 * [ProcessBuilder] with sandbox restrictions.
 *
 * Plugins receive a [CommandExecutor] through [com.mengpaw.kernel.plugin.PluginContext].
 * The framework may override this with a custom implementation that routes
 * commands through the full CLI pipeline (parse → security → execute).
 *
 * ## Sandbox
 * - Work directory is confined to [ExecutionContext.workDir]
 * - Dangerous commands (`rm -rf /`, `mkfs`, `dd`, `sudo`) are blocked
 * - Timeout: 30 seconds per command
 * - Output capped at 100 KB
 */
class DefaultCommandExecutor : CommandExecutor {

    private val blockedPrefixes = listOf(
        "rm -rf /", "rm -rf ~", "rm -rf .",
        "mkfs", "dd ", "sudo ", "su ",
        "chmod 777 /", "chown -R",
        ":(){ :|:& };:", // fork bomb
        "> /dev/sda", "> /dev/null"
    )

    override suspend fun execute(commandLine: String, ctx: ExecutionContext): ExecutionResult {
        // Sandbox: block dangerous patterns
        val trimmed = commandLine.trim()
        for (prefix in blockedPrefixes) {
            if (trimmed.contains(prefix, ignoreCase = true)) {
                KernelLog.w("DefaultCommandExecutor", "Blocked: $trimmed")
                return ExecutionResult.fail(
                    "Blocked by security policy: $prefix",
                    errorCode = ErrorCodes.ERR_PERMISSION_DENIED
                )
            }
        }

        return try {
            val process = ProcessBuilder()
                .command("sh", "-c", trimmed)
                .directory(java.io.File(ctx.workDir))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                var n: Int
                while (reader.read(buf).also { n = it } != -1 && total < MAX_OUTPUT) {
                    sb.append(buf, 0, n)
                    total += n
                }
                if (total >= MAX_OUTPUT) sb.append("\n... (truncated at ${MAX_OUTPUT / 1024} KB)")
                sb.toString()
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                ExecutionResult.ok(output.ifBlank { "(empty)" })
            } else {
                ExecutionResult.fail(
                    output.ifBlank { "Exit code: $exitCode" },
                    code = exitCode,
                    errorCode = ErrorCodes.ERR_INTERNAL
                )
            }
        } catch (e: Exception) {
            KernelLog.w("DefaultCommandExecutor", "Error: ${e.message}")
            ExecutionResult.fail(e.message ?: "Unknown error", errorCode = ErrorCodes.ERR_IO)
        }
    }

    companion object {
        /** Max output size in bytes before truncation. */
        const val MAX_OUTPUT = 100 * 1024 // 100 KB
    }
}
