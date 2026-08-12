// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.termux

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.CommandKeywords
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * Termux 桥插件 — MengPaw.shell → Termux → ubuntu (proot-distro) → miniconda → Python
 * 多层嵌套环境的命令面。
 *
 * 背景 (v0.36.3): LLM 直拼 `am startservice … RUN_COMMAND_ARGUMENTS` 会被 Linux
 * 通道的通用沙箱拦截 (python3 -c 前缀黑名单 / $ / && 元字符), 且 `am --esa` 逗号
 * 切分参数数组、多层引号嵌套必错。本插件把"登录 ubuntu + conda 环境 + 输出回传"
 * 全部封装进 [TermuxBridge], LLM 只给最后一层内容, 一次命令完成 写→执行→读回→清理。
 *
 * 安全: 内容先过内核 CommandMonitor 高危规则 (BLOCK/CONFIRM), 元字符策略不适用
 * (内容由 ubuntu 直接执行, 无本地 shell 拼接注入面)。
 */
class TermuxPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "termux-plugin",
        name = "Termux 桥",
        version = "", // 内置插件, 随 Shell APK 版本更新
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Termux 桥 — 通过 Termux 登录 ubuntu 执行命令与 conda 环境 Python " +
            "(Termux bridge — run commands/Python inside Termux + ubuntu + miniconda)",
        minCoreVersion = "0.36.2",
        commands = listOf("termux.status", "termux.python", "termux.ubuntu"),
        commandKeywords = mapOf(
            "status" to CommandKeywords(
                zh = listOf("状态", "探测", "环境", "termux", "ubuntu", "conda"),
                en = listOf("status", "detect", "env", "termux", "ubuntu", "conda")
            ),
            "python" to CommandKeywords(
                zh = listOf("python", "conda", "环境", "ubuntu", "执行脚本", "查询环境"),
                en = listOf("python", "conda", "env", "ubuntu", "run", "execute", "script")
            ),
            "ubuntu" to CommandKeywords(
                zh = listOf("ubuntu", "命令", "执行", "环境", "容器"),
                en = listOf("ubuntu", "command", "run", "exec", "container")
            )
        )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "status" to ::status,
        "python" to ::python,
        "ubuntu" to ::ubuntu
    )

    /** termux.status [--refresh] — 逐层探测 Termux/ubuntu/conda/python 与交换目录. */
    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val refresh = args.contains("--refresh")
        return ExecutionResult.ok(TermuxBridge.status(refresh))
    }

    /**
     * termux.python [--env <环境名>] [--timeout <秒>] <Python 代码>
     * 在 Termux→ubuntu→miniconda 环境执行 Python 并直接回传输出.
     */
    private suspend fun python(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val parsed = parseArgs(args)
        val code = parsed.rest.joinToString(" ")
        if (code.isBlank()) {
            return ExecutionResult.fail(
                "用法: termux.python [--env <环境名>] [--timeout <秒>] <Python 代码>\n" +
                    "在 Termux→ubuntu→miniconda 环境执行 Python 并回传输出。\n" +
                    "示例: termux.python \"import sys; print(sys.version)\"\n" +
                    "      termux.python --env py310 \"import numpy; print(numpy.__version__)\"\n" +
                    "先运行 termux.status 查看可用环境。",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        return TermuxBridge.runPython(code, parsed.env, parsed.timeoutMs)
    }

    /**
     * termux.ubuntu [--env <环境名>] [--timeout <秒>] <命令>
     * 登录 ubuntu (conda 环境内) 执行 shell 命令并直接回传输出.
     */
    private suspend fun ubuntu(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val parsed = parseArgs(args)
        val command = parsed.rest.joinToString(" ")
        if (command.isBlank()) {
            return ExecutionResult.fail(
                "用法: termux.ubuntu [--env <环境名>] [--timeout <秒>] <命令>\n" +
                    "在 Termux 的 ubuntu 容器内执行 shell 命令并回传输出。\n" +
                    "示例: termux.ubuntu \"pip list\"\n" +
                    "      termux.ubuntu --env py310 \"python -m pip --version\"",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        return TermuxBridge.runUbuntuCommand(command, parsed.env, parsed.timeoutMs)
    }

    /** 旗标解析 — --env <名> / --timeout <秒> (默认 120s, 上限 300s), 其余为正文. */
    private data class ParsedArgs(val env: String?, val timeoutMs: Long, val rest: List<String>)

    private fun parseArgs(args: List<String>): ParsedArgs {
        var env: String? = null
        var timeoutMs = 120_000L
        val rest = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--env" -> {
                    env = args.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("--") }
                    i += 2
                }
                "--timeout" -> {
                    args.getOrNull(i + 1)?.toLongOrNull()?.let { timeoutMs = it.coerceIn(5L, 300L) * 1000 }
                    i += 2
                }
                else -> {
                    rest.add(args[i])
                    i++
                }
            }
        }
        return ParsedArgs(env, timeoutMs, rest)
    }
}
