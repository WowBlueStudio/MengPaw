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

        // Safety check
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                auditLog("BLOCKED", command, -1, 0)
                return ExecResult("", "⛔ 危险命令已被 RootShell 安全策略拦截: ${pattern.pattern}", -1, 0)
            }
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }

            // Wait with timeout
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                auditLog("TIMEOUT", command, -1, System.currentTimeMillis() - start)
                return ExecResult(stdout, stderr + "\n⏱ 超时 (${timeoutMs}ms), 进程已被终止", -1, System.currentTimeMillis() - start)
            }

            val elapsed = System.currentTimeMillis() - start
            val exitCode = process.exitValue()
            auditLog(if (exitCode == 0) "SUCCESS" else "FAILED($exitCode)", command, exitCode, elapsed)

            // Save full output for agent.read access
            saveFullOutput(command, stdout, stderr, exitCode, elapsed)

            ExecResult(stdout, stderr, exitCode, elapsed)
        } catch (e: Exception) {
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
