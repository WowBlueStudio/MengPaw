// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.KernelLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Default [CommandExecutor] implementation — 沙箱检查后委托 [SessionShellPool]
 * 会话式进程池执行（每次调用自动初始化 cwd; 超时/取消由池销毁会话）。
 *
 * Plugins receive a [CommandExecutor] through [com.mengpaw.kernel.plugin.PluginContext].
 * The framework may override this with a custom implementation that routes
 * commands through the full CLI pipeline (parse → security → execute).
 *
 * ## Sandbox
 * - Dangerous command prefixes and shell metacharacters are blocked here
 *   （通过检查后才进池 — 池不改变安全面）
 * - Timeout: 30 seconds per command (enforced by the pool)
 * - Output capped at 100 KB (pool)
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
     * Execution via the session shell pool — 每次调用、汇报后自动初始化
     * （常驻会话进程，替代每次新起 sh -c）。沙箱检查完成后委托池执行。
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

        // 会话式进程池执行（每次调用自动初始化 cwd；超时/异常由池销毁会话）
        return SessionShellPool.execute(trimmed, ctx)
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

}
