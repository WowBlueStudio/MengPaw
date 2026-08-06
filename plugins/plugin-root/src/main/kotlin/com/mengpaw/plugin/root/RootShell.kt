// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.root

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Root shell execution engine with audit logging and safety guards.
 *
 * ## Safety
 * - Blocks catastrophic commands (rm -rf /, dd to /dev, mkfs, self-destruction)
 * - Logs every command to audit file
 * - Truncates output to prevent LLM context pollution
 * - Timeout prevents hung processes
 */
object RootShell {

    private val auditFile = File(DataPaths.BASE, "root_audit.log")
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** Catastrophic commands that are never allowed. */
    private val BLOCKED_PATTERNS = listOf(
        Regex("""rm\s+-rf?\s+/\b"""),                       // rm -rf /
        Regex("""rm\s+-rf?\s+/\*\b"""),                     // rm -rf /*
        Regex("""rm\s+-rf?\s+/system\b"""),                 // rm -rf /system
        Regex("""rm\s+-rf?\s+/boot\b"""),                   // rm -rf /boot
        Regex("""dd\s+if=.*\s+of=/dev/(block|sda|mmcblk)"""), // dd to block device
        Regex("""dd\s+if=/dev/zero\s+of=/dev/"""),          // zero out block device
        Regex("""mkfs\."""),                                 // make filesystem
        Regex("""cat\s+/dev/zero\s*>"""),                   // cat /dev/zero >
        Regex("""\b(data/data/com\.mengpaw\.shell)\b.*\b(rm|mv|dd)\b"""), // Self-destruction
    )

    /** rm 危险目标前缀 — 规范路径校验 (对齐 AgentExecutor.RM_BLOCKED_PREFIXES 思路)。
     *  命中任意前缀即拦截: 根目录 + 系统分区 + 应用私有数据。 */
    private val RM_BLOCKED_PREFIXES = listOf(
        "/system/", "/vendor/", "/product/", "/odm/",
        "/data/app/", "/data/dalvik-cache/", "/data/data/", "/data/user/",
        "/boot", "/recovery", "/apex/", "/cache/", "/dev/", "/etc/",
        "/proc/", "/sbin/", "/sys/", "/usr/", "/var/", "/bin/", "/lib/", "/lib64/"
    )

    /** 简易 shell 分词: 处理单引号/双引号/反斜杠, 还原引号内容 (不做变量/通配展开)。 */
    private fun tokenize(cmd: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote: Char? = null
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            when {
                inQuote != null -> {
                    if (c == inQuote) inQuote = null else cur.append(c)
                    i++
                }
                c == '\'' || c == '"' -> { inQuote = c; i++ }
                c == '\\' && i + 1 < cmd.length -> { cur.append(cmd[i + 1]); i += 2 }
                c.isWhitespace() -> { if (cur.isNotEmpty()) { tokens.add(cur.toString()); cur.clear() }; i++ }
                else -> { cur.append(c); i++ }
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        return tokens
    }

    /** 轻量路径规范化: 压缩重复斜杠, 展开 /. 与 /../ 段 (对齐 rm 实际解析行为)。 */
    private fun normalizePath(p: String): String {
        val parts = mutableListOf<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** 校验单个 rm 目标路径, 返回拦截原因; null = 放行。 */
    private fun checkRmTarget(rawTarget: String): String? {
        val t = normalizePath(rawTarget.trim().trimEnd(';', '|', '&', ')', '>', '<', '`'))
        // 根目录本身 (rm -rf / 、rm -rf /*、/.. 归约回根)
        if (t == "/" || t == "/*") return "根目录"
        if (t.startsWith("/*")) return "根目录通配"
        RM_BLOCKED_PREFIXES.firstOrNull { t.startsWith(it) }?.let { return it }
        return null
    }

    /** 解析 rm 命令参数做规范校验 — 修复正则黑名单绕过 (rm -r -f /、rm -rf 根通配、引号包裹、路径拼接等)。
     *  返回拦截原因; null = 放行。 */
    private fun checkRmCommand(command: String): String? {
        val tokens = tokenize(command)
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok != "rm" && tok.substringAfterLast('/') != "rm") { i++; continue }
            // 收集 rm 标志
            var j = i + 1
            while (j < tokens.size && tokens[j].startsWith("-")) {
                if (tokens[j] == "--") { j++; break }
                j++
            }
            // rm 带递归/强制标志时, 校验所有目标路径
            var hasRmFlag = false
            for (k in (i + 1) until j) {
                val flag = tokens[k]
                if (flag.contains('r') || flag.contains('f')) hasRmFlag = true
            }
            if (hasRmFlag) {
                while (j < tokens.size) {
                    val t = tokens[j]
                    if (t.startsWith("-") || t == "&&" || t == "||" || t == ";") break
                    checkRmTarget(t)?.let { return "rm 目标位于危险区域 ($t → $it)" }
                    j++
                }
            }
            i = j
        }
        return null
    }

    /** Shell 参数转义 — 单引号包裹 + 内部单引号转义, 防止 `;` `&&` `$()` 反引号等注入。 */
    fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    data class ExecResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val elapsedMs: Long
    ) {
        val success: Boolean get() = exitCode == 0
        val summary: String get() = buildString {
            if (success) appendLine("✅ 执行成功 (${elapsedMs}ms, exit=$exitCode)")
            else appendLine("❌ 执行失败 (${elapsedMs}ms, exit=$exitCode)")
            if (stdout.isNotBlank()) {
                appendLine("--- stdout ---")
                appendLine(stdout.take(4000))
                if (stdout.length > 4000) appendLine("... (截断, 共 ${stdout.length} 字符)")
            }
            if (stderr.isNotBlank()) {
                appendLine("--- stderr ---")
                appendLine(stderr.take(1000))
            }
        }
    }

    /**
     * Execute a command as root.
     * @param command The shell command to run (passed to su -c)
     * @param timeoutMs Maximum execution time
     * @return ExecResult with stdout, stderr, exit code, and elapsed time
     */
    fun execute(command: String, timeoutMs: Long = 30_000L): ExecResult {
        val start = System.currentTimeMillis()

        // Safety check — 正则黑名单 (保留, 防御纵深)
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                auditLog("BLOCKED", command, -1, 0)
                return ExecResult("", "⛔ 危险命令已被 RootShell 安全策略拦截: ${pattern.pattern}", -1, 0)
            }
        }
        // P1 修复: rm 参数规范校验 — 正则可被 rm -r -f /、rm -rf /*、引号包裹等绕过
        checkRmCommand(command)?.let { reason ->
            auditLog("BLOCKED", command, -1, 0)
            return ExecResult("", "⛔ 危险命令已被 RootShell 安全策略拦截: $reason", -1, 0)
        }

        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            // P1 修复: 两线程并行读 stdout/stderr, 防止单管道填满导致死锁
            var stdout = ""
            var stderr = ""
            val outReader = Thread {
                try { process?.inputStream?.bufferedReader()?.use { stdout = it.readText() } } catch (_: Exception) { }
            }.apply { isDaemon = true }
            val errReader = Thread {
                try { process?.errorStream?.bufferedReader()?.use { stderr = it.readText() } } catch (_: Exception) { }
            }.apply { isDaemon = true }
            outReader.start(); errReader.start()

            // Wait with timeout
            val finished = process!!.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process!!.destroyForcibly()
                auditLog("TIMEOUT", command, -1, System.currentTimeMillis() - start)
                outReader.join(5000); errReader.join(5000)
                return ExecResult(stdout, stderr + "\n⏱ 超时 (${timeoutMs}ms), 进程已被终止", -1, System.currentTimeMillis() - start)
            }
            outReader.join(5000); errReader.join(5000)

            val elapsed = System.currentTimeMillis() - start
            val exitCode = process!!.exitValue()
            auditLog(if (exitCode == 0) "SUCCESS" else "FAILED($exitCode)", command, exitCode, elapsed)

            // Save full output for agent.read access
            saveFullOutput(command, stdout, stderr, exitCode, elapsed)

            ExecResult(stdout, stderr, exitCode, elapsed)
        } catch (e: Exception) {
            // P1 修复: 异常路径也销毁进程, 防止进程泄漏
            try { process?.destroyForcibly() } catch (_: Exception) { }
            val elapsed = System.currentTimeMillis() - start
            auditLog("ERROR", command, -1, elapsed)
            ExecResult("", "执行异常: ${e.message}", -1, elapsed)
        }
    }

    /** Check if su is available and working. Returns su output or error. */
    fun checkSu(): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val stdout = process.inputStream.bufferedReader().readText()
            process.waitFor()
            Pair(process.exitValue() == 0, stdout.trim())
        } catch (e: Exception) {
            Pair(false, e.message ?: "unknown error")
        }
    }

    private fun auditLog(status: String, command: String, exitCode: Int, elapsedMs: Long) {
        try {
            auditFile.parentFile?.mkdirs()
            val cmdShort = command.take(200).replace("\n", "\\n")
            val line = "${dateFmt.format(Date())} | $status | su -c \"$cmdShort\" | exit=$exitCode | ${elapsedMs}ms\n"
            // Atomic write for audit integrity
            val tmp = File(auditFile.parentFile, "root_audit.tmp")
            val existing = if (auditFile.exists()) try { auditFile.readText() } catch (_: Exception) { "" } else ""
            tmp.writeText(existing + line)
            if (auditFile.exists()) auditFile.delete()
            tmp.renameTo(auditFile)
            if (tmp.exists()) tmp.delete()
        } catch (e: Exception) {
            ErrorCollector.report(e, "RootShell.auditLog")
        }
    }

    private fun saveFullOutput(command: String, stdout: String, stderr: String, exitCode: Int, elapsedMs: Long) {
        try {
            val outFile = File("/sdcard/root_out.txt")
            val tmp = File("/sdcard/root_out.tmp")
            tmp.writeText(buildString {
                appendLine("=== root.exec ===")
                appendLine("命令: su -c \"${command.take(500)}\"")
                appendLine("时间: ${dateFmt.format(Date())}")
                appendLine("退出码: $exitCode")
                appendLine("耗时: ${elapsedMs}ms")
                appendLine()
                appendLine("--- stdout ---")
                appendLine(stdout)
                if (stderr.isNotBlank()) {
                    appendLine("--- stderr ---")
                    appendLine(stderr)
                }
            })
            if (outFile.exists()) outFile.delete()
            tmp.renameTo(outFile)
            if (tmp.exists()) tmp.delete()
        } catch (_: Exception) {}
    }
}
