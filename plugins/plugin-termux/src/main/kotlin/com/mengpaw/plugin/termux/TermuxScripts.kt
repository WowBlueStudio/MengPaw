// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.termux

/**
 * Termux 桥纯逻辑 (JVM 可单测) — 脚本生成 / am 参数构造 / 结果解析 / 错误提示。
 * 与 [TermuxBridge] (IO 引擎) 分离: 本文件零 Android/协程依赖, 全部纯函数。
 */
internal object TermuxScripts {

    /** conda 环境名白名单 — 只允许字母/数字/下划线/点/连字符, 防脚本注入与路径穿越. */
    val ENV_NAME = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * 环境名校验 — 空白视为未指定 (合法); 非法返回拒绝原因 (供调用方直接呈现).
     * @return null = 合法; 否则 = 拒绝原因.
     */
    fun validateEnvName(env: String?): String? {
        val e = env?.trim().orEmpty()
        if (e.isEmpty()) return null
        return if (ENV_NAME.matches(e)) null
        else "环境名非法 (仅允许字母/数字/下划线/点/连字符): $e"
    }

    /** conda 根目录 (rootfs 内绝对路径) + 环境名 → python 可执行文件路径. */
    fun pythonForEnv(condaDir: String, env: String?): String =
        if (env.isNullOrBlank()) "$condaDir/bin/python"
        else "$condaDir/envs/$env/bin/python"

    /**
     * am --esa 的 payload — 只含 timeout + 脚本路径与重定向, 无逗号/引号嵌套.
     * timeout 包住整个 proot 进程: 死循环/卡死由 Termux 侧按时杀掉, 防止超时后
     * 插件放弃轮询而 ubuntu 内进程仍持续占用/写文件 (进程泄漏修复, v0.36.3 审计).
     */
    fun buildAmPayload(scriptPath: String, outPath: String, timeoutSec: Int): String =
        "timeout -k 10s ${timeoutSec}s proot-distro login $DISTRO -- bash $scriptPath > $outPath 2>&1"

    /** am startservice 参数数组 — 逐项直传, 不经 shell. */
    fun buildAmArgs(payload: String): List<String> = listOf(
        "am", "startservice", "--user", "0",
        "-n", TERMUX_COMPONENT,
        "-a", "com.termux.RUN_COMMAND",
        "--es", "com.termux.RUN_COMMAND_PATH", TERMUX_BASH,
        "--esa", "com.termux.RUN_COMMAND_ARGUMENTS", "-c,$payload",
        "--es", "com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME,
        "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "true"
    )

    /** ubuntu 内探测脚本 — 输出 WHOAMI/PWD/CONDA/ENVS/PYTHON 各一行. */
    fun buildProbeScript(): String = buildString {
        appendLine("echo \"WHOAMI=${'$'}(id -un)\"")
        appendLine("echo \"PWD=${'$'}(pwd)\"")
        appendLine("CONDA=\"\"")
        appendLine("for c in /root/miniconda3 /root/anaconda3 /root/miniconda /root/anaconda /home/*/miniconda3 /home/*/anaconda3 /opt/conda; do")
        appendLine("  if [ -z \"${'$'}CONDA\" ] && [ -f \"${'$'}c/etc/profile.d/conda.sh\" ]; then CONDA=\"${'$'}c\"; echo \"CONDA=${'$'}c\"; fi")
        appendLine("done")
        appendLine("echo \"ENVS=${'$'}(ls \"${'$'}CONDA/envs\" 2>/dev/null | tr '\\n' ' ')\"")
        appendLine("echo \"PYTHON=${'$'}(command -v python3)\"")
    }

    /** conda 环境 Python 执行脚本 — 直接调用环境内 python 二进制, 免 conda activate. */
    fun buildPythonScript(pyFile: String, marker: String): String = buildString {
        appendLine("<PYTHON> $pyFile")
        appendLine("rc=${'$'}?")
        appendLine("echo \"__MENGPAW_RC__${'$'}rc\"")
        appendLine("echo \"$marker\"")
    }

    /** ubuntu 通用命令脚本 — 先 source conda 并 activate, 再执行命令. */
    fun buildUbuntuScript(condaDir: String?, env: String?, command: String, marker: String): String = buildString {
        appendLine("#!/bin/bash")
        if (!condaDir.isNullOrBlank()) {
            appendLine("CONDA=\"$condaDir\"")
            appendLine("[ -f \"\$CONDA/etc/profile.d/conda.sh\" ] && source \"\$CONDA/etc/profile.d/conda.sh\"")
            if (!env.isNullOrBlank()) {
                appendLine("conda activate $env 2>/dev/null || true")
            }
        }
        appendLine(command)
        appendLine("rc=\$?")
        appendLine("echo \"__MENGPAW_RC__\$rc\"")
        appendLine("echo \"$marker\"")
    }

    /** 执行结果解析 — 从输出中提取 rc 与正文 (剥离标记行). */
    fun extractRunResult(output: String, marker: String): Pair<Int, String> {
        val rc = output.lineSequence()
            .firstOrNull { it.startsWith("__MENGPAW_RC__") }
            ?.removePrefix("__MENGPAW_RC__")?.trim()?.toIntOrNull() ?: 0
        val body = output.lines()
            .filterNot { it.startsWith("__MENGPAW_RC__") || it.startsWith(marker) }
            .joinToString("\n").trim()
        return rc to body
    }

    /** am 失败原因 → 可操作提示. */
    fun hintForAmError(amOutput: String): String = when {
        amOutput.contains("not found", ignoreCase = true) || amOutput.contains("unable to resolve", ignoreCase = true) ->
            "Termux 未安装或 RunCommandService 不存在 — 请从 F-Droid/GitHub 安装 Termux 并重试。"
        amOutput.contains("SecurityException", ignoreCase = true) ||
            amOutput.contains("not allowed to start service", ignoreCase = true) ->
            "Termux 未允许外部应用调用 — 在 Termux 中执行: echo \"allow-external-apps=true\" >> ~/.termux/termux.properties, 然后完全重启 Termux。"
        amOutput.contains("background", ignoreCase = true) || amOutput.contains("foreground service", ignoreCase = true) ->
            "Android 后台启动限制 — 请保持 MengPaw 在前台时执行。"
        else -> "请确认 Termux 已安装且 allow-external-apps 已开启。"
    }

    private const val TERMUX_COMPONENT = "com.termux/com.termux.app.RunCommandService"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val DISTRO = "ubuntu"
}
