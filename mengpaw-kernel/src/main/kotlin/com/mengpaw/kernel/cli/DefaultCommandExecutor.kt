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
 *
 * v0.36.x: 拆出 [sandboxCheck] 供 [com.mengpaw.kernel.security.CommandMonitor]
 * 对再解释 payload (sh -c / Termux) 复用; 元字符检查升级为结构化 —
 * 放行管道 `|` 与受控重定向 (`>`/`<`/`2>&1`), 拦截多命令串接 (`;` `&&` `||`)、
 * 后台 (`&`)、变量/命令替换 (`$` 反引号) 与换行内嵌多命令。
 */
class DefaultCommandExecutor : CommandExecutor {

    override suspend fun execute(commandLine: String, ctx: ExecutionContext): ExecutionResult {
        sandboxCheck(commandLine)?.let {
            return ExecutionResult.fail(it, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return kotlinx.coroutines.withTimeout(30_000L) { SessionShellPool.execute(commandLine, ctx) }
    }

    companion object {

        /** Dangerous command prefixes blocked by string match (case-insensitive). */
        private val blockedPrefixes = listOf(
            "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf .", "rm -rf *",
            "mkfs", "dd ",
            "chmod 777 /", "chmod -R 777", "chown -R",
            ":(){ :|:& };:", // fork bomb
            "> /dev/sda", "> /dev/null",
            "wget ", "curl ", "nc ", "telnet ", "ncat ",
            "python -c ", "python2 -c ", "python3 -c ", "perl -e ", "ruby -e ",
            "eval ", "base64 -d", "base64 --decode",
            "kill ", "pkill ", "poweroff", "reboot", "shutdown", "init 0", "init 6",
            "mount ", "fdisk ", "dd if=", "halt", "mv / ", "cp / "
        )
        // 注: su/sudo 不在前缀黑名单 (任意位置匹配会误伤 grep su file) —
        // 由 CommandMonitor 的 su-sudo 规则按命令位置 (行首/管道后) 拦截

        /**
         * 沙箱检查 (纯函数, 无副作用) — 危险前缀黑名单 + 结构化元字符检查。
         * 供 [execute] 与 [com.mengpaw.kernel.security.CommandMonitor] 复用;
         * 返回拒绝原因 (应阻止执行) 或 null (通过)。
         */
        fun sandboxCheck(commandLine: String): String? {
            val trimmed = commandLine.trim()
            if (trimmed.isBlank()) {
                return "空命令"
            }

            checkMetaChars(trimmed)?.let { return it }

            for (prefix in blockedPrefixes) {
                if (trimmed.startsWith(prefix, ignoreCase = true) ||
                    (" " + trimmed).contains(" " + prefix, ignoreCase = true)
                ) {
                    return "安全策略禁止: $prefix (可用 self.tools 查看可用命令)"
                }
            }
            return null
        }

        /**
         * 结构化元字符检查:
         * - 放行: 管道 `|`、重定向 `>` `>>` `<`、fd 重定向 `2>&1`/`1>&2`、通配符、引号
         * - 拦截: `;` `&&` `||` (多命令串接)、`&` 后台、`$` (变量/命令替换)、反引号、换行
         */
        private fun checkMetaChars(cmd: String): String? {
            var inQuote: Char? = null
            var escape = false
            var i = 0
            while (i < cmd.length) {
                val c = cmd[i]
                if (escape) { escape = false; i++; continue }
                if (c == '\\') { escape = true; i++; continue }
                if (inQuote != null) {
                    if (c == inQuote) { inQuote = null; i++; continue }
                    // 双引号内: $ 与反引号仍由 shell 解释 (变量展开/命令替换), 必须继续检查;
                    // 单引号内全部字面, 安全跳过
                    if (inQuote == '"') {
                        when (c) {
                            '$' -> return "安全策略: 不允许使用 '\$' (变量/命令替换)"
                            '`' -> return "安全策略: 不允许使用反引号 (命令替换)"
                        }
                    }
                    i++; continue
                }
                if (c == '\'' || c == '"') { inQuote = c; i++; continue }
                when (c) {
                    ';' -> return "安全策略: 不允许使用 ';' (多命令串接)"
                    '`' -> return "安全策略: 不允许使用反引号 (命令替换)"
                    '$' -> return "安全策略: 不允许使用 '\$' (变量/命令替换)"
                    '\n', '\r' -> return "安全策略: 不允许换行 (内嵌多命令)"
                    '&' -> {
                        val prev = if (i > 0) cmd[i - 1] else ' '
                        val next = if (i + 1 < cmd.length) cmd[i + 1] else ' '
                        if (prev.isDigit() || prev == '>') { i++; continue } // 2>&1 / >& — fd 重定向放行
                        if (next == '&') return "安全策略: 不允许使用 '&&' (多命令串接)"
                        return "安全策略: 不允许使用 '&' (后台执行)"
                    }
                    '|' -> {
                        if (i + 1 < cmd.length && cmd[i + 1] == '|') {
                            return "安全策略: 不允许使用 '||' (多命令串接)"
                        }
                        // 单 | 管道放行
                    }
                }
                i++
            }
            return null
        }
    }

}
