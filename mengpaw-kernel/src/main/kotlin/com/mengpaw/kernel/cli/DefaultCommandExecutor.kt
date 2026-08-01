// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.KernelLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

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
 * - Dangerous commands and shell metacharacters are blocked
 * - Timeout: 30 seconds per command (enforced)
 * - Output capped at 100 KB
 * - Blocking I/O runs on [Dispatchers.IO] for coroutine cancellation support
 */
class DefaultCommandExecutor : CommandExecutor {

    /** Dangerous command prefixes blocked by string match (case-insensitive). */
    private val blockedPrefixes = listOf(
        "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf .", "rm -rf *",
        "mkfs", "dd ", "sudo ", "su ",
        "chmod 777 /", "chmod -R 777", "chown -R",
        ":(){ :|:& };:", // fork bomb
        "> /dev/sda", "> /dev/null",
        "wget ", "curl ", "nc ", "telnet ", "ncat ",
        "python -c ", "python2 -c ", "python3 -c ", "perl -e ", "ruby -e ",
        "eval ", "base64 -d", "base64 --decode",
        "kill ", "pkill ", "poweroff", "reboot", "shutdown", "init 0", "init 6",
        "mount ", "fdisk ", "dd if=", "halt", "mv / ", "cp / "
    )

    /** Shell metacharacters that allow multi-command injection. */
    private val shellMetacharacters = setOf(';', '|', '`', '&', '$')

    override suspend fun execute(commandLine: String, ctx: ExecutionContext): ExecutionResult {
        return kotlinx.coroutines.withTimeout(30_000L) {
            executeBlocking(commandLine, ctx)
        }
    }

    /**
     * Blocking execution on [Dispatchers.IO] so [withTimeout] cancellation works
     * via coroutine cancellation (interrupts the IO thread).
     */
    private suspend fun executeBlocking(commandLine: String, ctx: ExecutionContext): ExecutionResult {
        // Sandbox: block dangerous patterns
        val trimmed = commandLine.trim()
        if (trimmed.isBlank()) {
            return ExecutionResult.fail("Empty command", errorCode = ErrorCodes.ERR_INTERNAL)
        }

        // Check shell metacharacters that indicate multi-command attempts
        if (hasShellMetacharacters(trimmed)) {
            KernelLog.w("DefaultCommandExecutor", "Blocked (shell metacharacters): $trimmed")
            return ExecutionResult.fail(
                "Blocked by security policy: shell metacharacters not allowed (;, |, `, &, \$)",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }

        for (prefix in blockedPrefixes) {
            if (trimmed.startsWith(prefix, ignoreCase = true) ||
                (" " + trimmed).contains(" " + prefix, ignoreCase = true)
            ) {
                KernelLog.w("DefaultCommandExecutor", "Blocked: $trimmed")
                return ExecutionResult.fail(
                    "Blocked by security policy: $prefix. Use self.tools to see available commands.",
                    errorCode = ErrorCodes.ERR_PERMISSION_DENIED
                )
            }
        }

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val process = ProcessBuilder()
                    .command("sh", "-c", trimmed)
                    .directory(File(ctx.workDir))
                    .redirectErrorStream(true)
                    .start()

                // Read output with timeout + cancellation support
                val output = readOutputWithTimeout(process)
                val finished = process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@withContext ExecutionResult.fail("Process timed out (30s)", errorCode = ErrorCodes.ERR_TIMEOUT)
                }
                val exitCode = process.exitValue()

                if (exitCode == 0) {
                    ExecutionResult.ok(output.ifBlank { "(empty)" })
                } else {
                    ExecutionResult.fail(
                        output.ifBlank { "Exit code: $exitCode" },
                        code = exitCode,
                        errorCode = ErrorCodes.ERR_INTERNAL
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ExecutionResult.fail("Command timed out (30s)", errorCode = ErrorCodes.ERR_TIMEOUT)
            } catch (e: Exception) {
                KernelLog.w("DefaultCommandExecutor", "Error: ${e.message}")
                ExecutionResult.fail(e.message ?: "Unknown error", errorCode = ErrorCodes.ERR_IO)
            }
        }
    }

    /**
     * Read process output with 100KB cap.
     * This runs on [Dispatchers.IO] so it can be interrupted by coroutine cancellation.
     */
    private fun readOutputWithTimeout(process: Process): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var total = 0
        process.inputStream.bufferedReader().use { reader ->
            var n: Int
            while (reader.read(buf).also { n = it } != -1 && total < MAX_OUTPUT) {
                sb.append(buf, 0, n)
                total += n
            }
        }
        if (total >= MAX_OUTPUT) sb.append("\n... (truncated at ${MAX_OUTPUT / 1024} KB)")
        return sb.toString()
    }

    /**
     * Check if the command contains shell metacharacters outside of safe contexts.
     */
    private fun hasShellMetacharacters(cmd: String): Boolean {
        var inQuote: Char? = null
        var escaped = false
        for (c in cmd) {
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (inQuote != null) {
                if (c == inQuote) inQuote = null
                continue
            }
            if (c == '\'' || c == '"') { inQuote = c; continue }
            if (c in shellMetacharacters) return true
        }
        return false
    }

    companion object {
        /** Max output size in bytes before truncation. */
        const val MAX_OUTPUT = 100 * 1024 // 100 KB
    }
}
