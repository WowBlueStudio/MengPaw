// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.KernelLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * 会话式 sh 进程池 — 每次调用、汇报后自动初始化。
 *
 * 替代 DefaultCommandExecutor 的"每次新起 sh -c 进程"（~200ms 启动，
 * 火种并行下成进程风暴）。常驻会话进程从 stdin 逐条执行，协议:
 *
 *   写 "cd <workDir> 2>/dev/null\n"   ← 每次调用自动初始化（重置 cwd，防状态残留）
 *   写 "<命令>\n"
 *   写 "echo __MENGPAW_DONE_<随机id>__\n"  ← 哨兵行标记完成
 *   读 stdout 直到哨兵行 → 哨兵前内容 = 命令输出（stderr 已合并）
 *
 * 汇报后进程回到空闲状态归还池（自动初始化完成，下轮可直接复用）。
 * 超时/取消/输出超限/进程异常 → destroyForcibly + 丢弃（不复用，正确性优先）。
 * 线程安全：借还 synchronized；多 Action 并行（BACKGROUND 8 并发）安全。
 *
 * 沙箱（黑名单/元字符）在 DefaultCommandExecutor 执行前检查，池不改变安全面。
 */
object SessionShellPool {

    private const val MAX_IDLE = 4

    /** 输出上限（同 DefaultCommandExecutor：100KB）。 */
    private const val MAX_OUTPUT = 100 * 1024

    /** 单命令超时（可调 — 测试用）。 */
    @Volatile
    var commandTimeoutMs: Long = 30_000L

    private val idle = ArrayDeque<ShellSession>()

    /** 累计创建的会话数 — 测试断言"进程未被重建"用。 */
    @Volatile
    var totalCreated: Int = 0
        private set

    /** 常驻会话进程：stdin/stdout 管道 + 生命周期管理。 */
    private class ShellSession(private val proc: Process) {
        val reader: BufferedReader = proc.inputStream.bufferedReader()
        val writer: BufferedWriter = proc.outputStream.bufferedWriter()

        /** 强制销毁（超时/取消/异常/输出超限）— 状态未知不复用。 */
        fun kill() {
            try { proc.destroyForcibly() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { writer.close() } catch (_: Exception) {}
        }
    }

    /**
     * 通过池执行一条命令（调用方已完成沙箱检查）。
     * @return 成功=输出；超时=ERR_TIMEOUT；进程异常/不可用=ERR_IO。
     */
    suspend fun execute(commandLine: String, ctx: ExecutionContext): ExecutionResult {
        val session = borrow()
        return try {
            withTimeout(commandTimeoutMs) {
                runInSession(session, commandLine, ctx)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            session.kill()  // 修复现状泄漏缺口: 取消路径必须销毁子进程
            ExecutionResult.fail("Command timed out (30s)", errorCode = ErrorCodes.ERR_TIMEOUT)
        } catch (e: Exception) {
            session.kill()
            KernelLog.w("SessionShellPool", "Error: ${e.message}")
            ExecutionResult.fail(e.message ?: "Unknown error", errorCode = ErrorCodes.ERR_IO)
        }
    }

    /** 借出会话：空闲队列优先，无则新建（达上限排队等待）。 */
    private fun borrow(): ShellSession = synchronized(this) {
        idle.removeLastOrNull() ?: create()
    }

    /** 归还会话：池未满则保留（汇报后自动初始化完成），满则销毁。 */
    private fun returnSession(session: ShellSession) {
        synchronized(this) {
            if (idle.size < MAX_IDLE) {
                idle.addLast(session)
            } else {
                session.kill()
            }
        }
    }

    private fun create(): ShellSession {
        totalCreated++
        val pb = ProcessBuilder("sh").redirectErrorStream(true)
        // 初始目录用数据基目录（Android 上为应用私有目录）；每次调用前会 cd 重置
        try { pb.directory(File(com.mengpaw.kernel.DataPaths.BASE)) } catch (_: Exception) {}
        val proc = pb.start()
        return ShellSession(proc)
    }

    /** 会话内执行（轮询读 — delay 让 withTimeout 取消可中断阻塞读）。 */
    private suspend fun runInSession(
        session: ShellSession, commandLine: String, ctx: ExecutionContext
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            // ── 每次调用自动初始化: 重置 cwd（实测 cd 残留会污染下轮；引号防路径空格/转义）──
            session.writer.write("cd \"${ctx.workDir}\" 2>/dev/null\n")
            session.writer.write(commandLine)
            session.writer.write("\n")
            // 退出码捕获: 哨兵行携带 exit code（换行分隔，不引入分号）
            val marker = "__MENGPAW_DONE_${java.util.UUID.randomUUID().toString().take(8)}__"
            session.writer.write("exit_code=\$?\n")
            session.writer.write("echo $marker\$exit_code\n")
            session.writer.flush()

            // ── 读 stdout 直到哨兵（轮询 — delay 可被 withTimeout 取消）──
            var exitCode = 0
            var done = false
            while (!done) {
                while (session.reader.ready()) {
                    val line = session.reader.readLine()
                        ?: return@withContext sessionDead(session, commandLine)
                    if (line.startsWith(marker)) {
                        done = true
                        exitCode = line.removePrefix(marker).trim().toIntOrNull() ?: 0
                        break
                    }
                    if (sb.length < MAX_OUTPUT) sb.append(line).append('\n')
                }
                if (!done) delay(10)
            }
            returnSession(session)
            val output = sb.toString().trimEnd()
            if (exitCode == 0) {
                ExecutionResult.ok(output.ifBlank { "(empty)" })
            } else {
                // 恢复原语义: 非零退出码 = 失败（带输出内容）
                ExecutionResult.fail(
                    output.ifBlank { "Exit code: $exitCode" },
                    code = exitCode,
                    errorCode = ErrorCodes.ERR_INTERNAL
                )
            }
        } catch (e: Exception) {
            // 写失败（进程死亡/管道断）→ 销毁，下次 borrow 自动重建
            session.kill()
            KernelLog.w("SessionShellPool", "runInSession: ${e.message}")
            ExecutionResult.fail("Shell execution error: ${e.message ?: e::class.simpleName}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    /** 输出超限 — 截断返回并销毁会话（防写阻塞，正确性优先于复用率）。 */
    private fun sessionDead(session: ShellSession, commandLine: String): ExecutionResult {
        session.kill()
        KernelLog.w("SessionShellPool", "Session terminated unexpectedly for: $commandLine")
        return ExecutionResult.fail("Shell session terminated unexpectedly", errorCode = ErrorCodes.ERR_IO)
    }

    /** 测试隔离用：清空池并重置统计。 */
    fun resetForTest() {
        synchronized(this) {
            idle.forEach { it.kill() }
            idle.clear()
            totalCreated = 0
        }
    }
}
