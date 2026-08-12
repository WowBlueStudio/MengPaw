// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.termux

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.security.CommandMonitor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Termux 桥执行引擎 — MengPaw.shell → Termux (RunCommandService) → ubuntu
 * (proot-distro) → miniconda → Python 的多层嵌套环境调用。
 *
 * 为什么不用 am 字符串通道 (LLM 直拼 am startservice): ① `am --esa` 按逗号切分
 * 参数数组, Python 代码里的逗号会把命令切碎; ② 多层引号嵌套 LLM 必拼错;
 * ③ 通用 Linux 通道的元字符/前缀黑名单会误伤合法内容。本引擎改为:
 *   1. 插件把代码/命令写入公共交换目录 (/sdcard/MengPaw/termux/) 的脚本文件;
 *   2. am 只传"登录 ubuntu 执行该脚本 + 输出重定向"这一条无逗号 payload;
 *   3. 轮询输出文件直到完成标记, 读回并清理 — 一次命令完成全流程。
 * 安全: 内容先过 [CommandMonitor.evaluateRulesOnly] (高危规则 BLOCK/CONFIRM),
 * 元字符策略不再适用 (内容由 ubuntu 直接执行, 无本地 shell 拼接注入面)。
 *
 * 效率: 状态探测结果缓存 30s (--refresh 强制); 探测/执行共用同一脚本通道;
 * 命令串行化 (Termux 服务单飞), 轮询间隔 300ms。
 */
object TermuxBridge {

    /** 公共交换目录 — 应用与 Termux/proot 双侧可读写 (需两边权限). */
    const val EXCHANGE_DIR = "/sdcard/MengPaw/termux"

    private const val TERMUX_COMPONENT = "com.termux/com.termux.app.RunCommandService"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val DISTRO = "ubuntu"

    private const val AM_TIMEOUT_MS = 10_000L
    private const val POLL_INTERVAL_MS = 300L
    private const val OUTPUT_CAP = 100_000
    private const val PROBE_TIMEOUT_MS = 90_000L
    private const val STATUS_TTL_MS = 30_000L

    /** 探测/执行串行化 — Termux RUN_COMMAND 服务不宜并发. */
    private val runMutex = Mutex()

    // ── 状态缓存 (纯字符串报告) ──
    @Volatile private var statusCache: String? = null
    @Volatile private var statusCachedAt = 0L
    @Volatile private var condaDirCache: String? = null
    @Volatile private var envsCache: List<String> = emptyList()
    @Volatile private var envProbedAt = 0L

    // ═══════════════════════════════════════════════════════════════
    // 纯逻辑 (JVM 可单测)
    // ═══════════════════════════════════════════════════════════════

    /** conda 根目录 (rootfs 内绝对路径) + 环境名 → python 可执行文件路径. */
    internal fun pythonForEnv(condaDir: String, env: String?): String =
        if (env.isNullOrBlank()) "$condaDir/bin/python"
        else "$condaDir/envs/$env/bin/python"

    /** am --esa 的 payload — 只含脚本路径与重定向, 无逗号/引号嵌套. */
    internal fun buildAmPayload(scriptPath: String, outPath: String): String =
        "proot-distro login $DISTRO -- bash $scriptPath > $outPath 2>&1"

    /** am startservice 参数数组 — 逐项直传, 不经 shell. */
    internal fun buildAmArgs(payload: String): List<String> = listOf(
        "am", "startservice", "--user", "0",
        "-n", TERMUX_COMPONENT,
        "-a", "com.termux.RUN_COMMAND",
        "--es", "com.termux.RUN_COMMAND_PATH", TERMUX_BASH,
        "--esa", "com.termux.RUN_COMMAND_ARGUMENTS", "-c,$payload",
        "--es", "com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME,
        "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "true"
    )

    /** ubuntu 内探测脚本 — 输出 WHOAMI/PWD/CONDA/ENVS/PYTHON 各一行. */
    internal fun buildProbeScript(): String = buildString {
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
    internal fun buildPythonScript(pyFile: String, marker: String): String = buildString {
        appendLine("<PYTHON> $pyFile")
        appendLine("rc=${'$'}?")
        appendLine("echo \"__MENGPAW_RC__${'$'}rc\"")
        appendLine("echo \"$marker\"")
    }

    /** ubuntu 通用命令脚本 — 先 source conda 并 activate, 再执行命令. */
    internal fun buildUbuntuScript(condaDir: String?, env: String?, command: String, marker: String): String = buildString {
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
    internal fun extractRunResult(output: String, marker: String): Pair<Int, String> {
        val rc = output.lineSequence()
            .firstOrNull { it.startsWith("__MENGPAW_RC__") }
            ?.removePrefix("__MENGPAW_RC__")?.trim()?.toIntOrNull() ?: 0
        val body = output.lines()
            .filterNot { it.startsWith("__MENGPAW_RC__") || it.startsWith(marker) }
            .joinToString("\n").trim()
        return rc to body
    }

    /** 内容安全扫描 — 复用内核高危规则 (BLOCK 拒绝 / CONFIRM 弹窗, 30s 超时默认拒). */
    internal suspend fun checkContent(content: String): String? =
        CommandMonitor.evaluateRulesOnly(content, allowUserConfirm = true)

    // ═══════════════════════════════════════════════════════════════
    // 命令入口
    // ═══════════════════════════════════════════════════════════════

    /** termux.status [--refresh] — 逐层探测并给出可操作提示. */
    suspend fun status(refresh: Boolean): String {
        val now = System.currentTimeMillis()
        if (!refresh && statusCache != null && now - statusCachedAt < STATUS_TTL_MS) {
            return statusCache!!
        }
        // runScript 内部已串行化 (runMutex), 此处不再持锁 — 避免与 runScript 死锁
        val report = probeReport()
        statusCache = report
        statusCachedAt = now
        return report
    }

    /** termux.python [--env <环境名>] <代码> — 在 conda 环境执行 Python 并回传输出. */
    suspend fun runPython(code: String, env: String?, timeoutMs: Long): ExecutionResult {
        if (code.isBlank()) {
            return ExecutionResult.fail(
                "用法: termux.python [--env <环境名>] <Python 代码>\n" +
                    "在 Termux→ubuntu→miniconda 环境执行 Python 并回传输出。先 termux.status 查看可用环境。",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        checkContent(code)?.let {
            return ExecutionResult.fail("Python 代码命中安全规则, 已阻止: $it", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        val (conda, envs) = ensureEnvInfo()
        if (conda.isNullOrBlank()) {
            return ExecutionResult.fail(
                    "未检测到 ubuntu 内的 conda/miniconda。请先运行 termux.status 查看安装状态, " +
                    "并在 ubuntu 中安装 miniconda: proot-distro login ubuntu -- bash -c \"curl -fsSL https://mirrors.tuna.tsinghua.edu.cn/anaconda/miniconda/Miniconda3-latest-Linux-x86_64.sh -o /tmp/mc.sh && bash /tmp/mc.sh -b\"",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val targetEnv = env?.takeIf { it.isNotBlank() }
        if (targetEnv != null && envs.none { it == targetEnv }) {
            return ExecutionResult.fail(
                "conda 环境不存在: $targetEnv。可用环境: ${(listOf("base") + envs).joinToString(", ")}",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val py = pythonForEnv(conda, targetEnv)
        val runId = newRunId()
        val pyName = "run_$runId.py"
        val script = buildPythonScript("/sdcard/MengPaw/termux/$pyName", "__MENGPAW_DONE_$runId")
            .replace("<PYTHON>", py)
        return runScript(script, listOf(pyName to code), timeoutMs) { out, rc ->
            if (rc == 0) ExecutionResult.ok(out.ifBlank { "(empty)" })
            else ExecutionResult.fail(
                "Python 退出码 $rc:\n${out.ifBlank { "(无输出)" }}",
                errorCode = ErrorCodes.ERR_INTERNAL
            )
        }
    }

    /** termux.ubuntu [--env <环境名>] <命令> — 登录 ubuntu (conda 环境内) 执行命令. */
    suspend fun runUbuntuCommand(command: String, env: String?, timeoutMs: Long): ExecutionResult {
        if (command.isBlank()) {
            return ExecutionResult.fail(
                "用法: termux.ubuntu [--env <环境名>] <命令>\n在 Termux 的 ubuntu 容器内执行 shell 命令并回传输出。",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        checkContent(command)?.let {
            return ExecutionResult.fail("命令命中安全规则, 已阻止: $it", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        val (conda, _) = ensureEnvInfo()
        val runId = newRunId()
        val marker = "__MENGPAW_DONE_$runId"
        val script = buildUbuntuScript(conda, env?.takeIf { it.isNotBlank() }, command, marker)
        return runScript(script, emptyList(), timeoutMs) { out, rc ->
            if (rc == 0) ExecutionResult.ok(out.ifBlank { "(empty)" })
            else ExecutionResult.fail(
                "命令退出码 $rc:\n${out.ifBlank { "(无输出)" }}",
                errorCode = ErrorCodes.ERR_INTERNAL
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部实现
    // ═══════════════════════════════════════════════════════════════

    private suspend fun probeReport(): String = buildString {
        appendLine("## Termux 桥状态")
        val exchangeOk = ensureExchangeWritable()
        appendLine("- 交换目录: $EXCHANGE_DIR — ${if (exchangeOk) "✅ 可写" else "❌ 不可写 (需 MengPaw『所有文件访问』权限 + Termux termux-setup-storage)"}")
        if (!exchangeOk) {
            appendLine("- 提示: ① 系统设置→应用→MengPaw→所有文件访问 开启; ② Termux 内执行 termux-setup-storage 授权存储; ③ 创建 $EXCHANGE_DIR")
            return@buildString
        }
        val probe = probeUbuntu(PROBE_TIMEOUT_MS)
        appendLine(probe)
    }

    /** 端到端探测: am 启动 Termux → ubuntu 内脚本输出环境信息. */
    private suspend fun probeUbuntu(timeoutMs: Long): String {
        val runId = newRunId()
        val marker = "__MENGPAW_DONE_$runId"
        val script = buildProbeScript() + "\nrc=\$?\necho \"__MENGPAW_RC__\$rc\"\necho \"$marker\""
        val result = runScript(script, emptyList(), timeoutMs) { out, rc ->
            if (rc == 0) ExecutionResult.ok(out) else ExecutionResult.fail(out.ifBlank { "(无输出)" }, errorCode = ErrorCodes.ERR_INTERNAL)
        }
        if (result.success) {
            val out = result.output
            val whoami = out.lineSequence().firstOrNull { it.startsWith("WHOAMI=") }?.substringAfter("=") ?: "?"
            val pwd = out.lineSequence().firstOrNull { it.startsWith("PWD=") }?.substringAfter("=") ?: "?"
            val conda = out.lineSequence().firstOrNull { it.startsWith("CONDA=") }?.substringAfter("=")
            val envs = out.lineSequence().firstOrNull { it.startsWith("ENVS=") }?.substringAfter("=")
                ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
            val py = out.lineSequence().firstOrNull { it.startsWith("PYTHON=") }?.substringAfter("=") ?: ""
            condaDirCache = conda
            envsCache = envs
            envProbedAt = System.currentTimeMillis()
            return buildString {
                appendLine("- Ubuntu (proot-distro): ✅ 用户 $whoami · 目录 $pwd")
                appendLine("- Miniconda: ${conda?.let { "✅ $it" } ?: "❌ 未检测到 (可在 ubuntu 内安装 miniconda)"}")
                appendLine("- Conda 环境: ${(listOf("base") + envs).joinToString(", ")}")
                appendLine("- Python: ${py.ifBlank { "❌ 未检测到 python3" }}")
                appendLine("- 用法: termux.python [--env <环境>] <代码> / termux.ubuntu <命令>")
            }
        } else {
            // 失败信息已含具体层 (am/存储/超时), 直接呈现
            return buildString {
                appendLine("- Ubuntu 探测失败: ${result.output.take(500)}")
            }
        }
    }

    /** conda/env 探测缓存 — 无缓存或过期时先跑一次探测. */
    private suspend fun ensureEnvInfo(): Pair<String?, List<String>> {
        val now = System.currentTimeMillis()
        if (condaDirCache != null && now - envProbedAt < STATUS_TTL_MS) return condaDirCache to envsCache
        // 探测会更新缓存; 探测失败时返回空并保留提示
        probeUbuntu(PROBE_TIMEOUT_MS)
        return condaDirCache to envsCache
    }

    /** 通用脚本执行引擎: 写文件 → am 启动 → 轮询输出 → 解析 → 清理. */
    private suspend fun runScript(
        scriptBody: String,
        extraFiles: List<Pair<String, String>>,
        timeoutMs: Long,
        build: (output: String, rc: Int) -> ExecutionResult
    ): ExecutionResult = runMutex.withLock {
        val dir = File(EXCHANGE_DIR)
        if (!ensureExchangeWritable()) {
            return@withLock ExecutionResult.fail(
                "公共交换目录不可写: $EXCHANGE_DIR — 请确认 MengPaw『所有文件访问』已授权、Termux 已 termux-setup-storage",
                errorCode = ErrorCodes.ERR_IO
            )
        }
        val runId = newRunId()
        val script = File(dir, "run_$runId.sh")
        val out = File(dir, "run_$runId.out")
        try {
            extraFiles.forEach { (name, content) -> File(dir, name).writeText(content) }
            script.writeText("#!/bin/bash\n$scriptBody")
            val payload = buildAmPayload("/sdcard/MengPaw/termux/run_$runId.sh", "/sdcard/MengPaw/termux/run_$runId.out")
            val am = launchAm(buildAmArgs(payload))
            if (!am.ok) {
                return@withLock ExecutionResult.fail(
                    "Termux 启动失败: ${am.output.take(300).trim()}\n${hintForAmError(am.output)}",
                    errorCode = ErrorCodes.ERR_PERMISSION_DENIED
                )
            }
            val marker = "__MENGPAW_DONE_$runId"
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val text = readOut(out)
                if (text != null && text.contains(marker)) {
                    val (rc, body) = extractRunResult(text, marker)
                    return@withLock build(body.take(OUTPUT_CAP), rc)
                }
                delay(POLL_INTERVAL_MS)
            }
            val partial = readOut(out)?.take(OUTPUT_CAP)
            return@withLock ExecutionResult.fail(
                "Termux 执行超时 (${timeoutMs / 1000}s)${partial?.let { "\n\n部分输出:\n$it" } ?: " — 未产生输出 (检查 Termux 存储权限与 allow-external-apps)"}",
                errorCode = ErrorCodes.ERR_TIMEOUT
            )
        } finally {
            // 尽力清理, 失败不阻塞
            try { script.delete() } catch (_: Exception) {}
            try { out.delete() } catch (_: Exception) {}
            extraFiles.forEach { (name, _) -> try { File(dir, name).delete() } catch (_: Exception) {} }
        }
    }

    private fun newRunId(): String = "t${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(0xffff)}"

    /** 交换目录可写性探测 — mkdirs + 写探针. */
    private fun ensureExchangeWritable(): Boolean {
        return try {
            val dir = File(EXCHANGE_DIR)
            if (!dir.exists() && !dir.mkdirs()) false
            else {
                val probe = File(dir, ".probe")
                probe.writeText("ok")
                probe.delete()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readOut(out: File): String? = try {
        if (!out.exists() || out.length() == 0L) null else out.readText()
    } catch (_: Exception) {
        null
    }

    private data class AmResult(val ok: Boolean, val output: String)

    private suspend fun launchAm(args: List<String>): AmResult = withContext(Dispatchers.IO) {
        val proc = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return@withContext AmResult(false, "无法启动 am 进程: ${e.message}")
        }
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + AM_TIMEOUT_MS
        try {
            val reader = proc.inputStream.bufferedReader()
            while (System.currentTimeMillis() < deadline) {
                while (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (sb.length + line.length < 2000) sb.append(line).append('\n')
                }
                if (!proc.isAlive && !reader.ready()) break
                delay(50)
            }
            if (proc.isAlive) {
                proc.destroyForcibly()
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            AmResult(proc.exitValue() == 0, sb.toString().trim())
        } catch (e: Exception) {
            try { proc.destroyForcibly() } catch (_: Exception) {}
            AmResult(false, "am 执行异常: ${e.message}")
        }
    }

    /** am 失败原因 → 可操作提示. */
    internal fun hintForAmError(amOutput: String): String = when {
        amOutput.contains("not found", ignoreCase = true) || amOutput.contains("unable to resolve", ignoreCase = true) ->
            "Termux 未安装或 RunCommandService 不存在 — 请从 F-Droid/GitHub 安装 Termux 并重试。"
        amOutput.contains("SecurityException", ignoreCase = true) ||
            amOutput.contains("not allowed to start service", ignoreCase = true) ->
            "Termux 未允许外部应用调用 — 在 Termux 中执行: echo \"allow-external-apps=true\" >> ~/.termux/termux.properties, 然后完全重启 Termux。"
        amOutput.contains("background", ignoreCase = true) || amOutput.contains("foreground service", ignoreCase = true) ->
            "Android 后台启动限制 — 请保持 MengPaw 在前台时执行。"
        else -> "请确认 Termux 已安装且 allow-external-apps 已开启。"
    }
}
