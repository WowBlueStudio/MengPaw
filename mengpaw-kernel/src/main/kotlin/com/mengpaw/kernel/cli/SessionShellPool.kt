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

    /** 并发执行上限 — 防多 Action 并行 × 多 Agent 的进程风暴（WIP 闸）。 */
    private const val MAX_CONCURRENT = 4

    /** 输出上限（同 DefaultCommandExecutor：100KB）。 */
    private const val MAX_OUTPUT = 100 * 1024

    /** 单命令超时（可调 — 测试用）。 */
    @Volatile
    var commandTimeoutMs: Long = 30_000L

    private val idle = ArrayDeque<ShellSession>()

    /** 并发执行上限（Semaphore）— 防多 Action 并行 × 多 Agent 的进程风暴回潮。 */
    private val concurrency = java.util.concurrent.Semaphore(MAX_CONCURRENT)

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
        concurrency.acquire()  // 并发上限（WIP 闸）— MAX_CONCURRENT 内排队
        var attempt = 0
        while (true) {
            val session = try {
                borrow()
            } catch (e: Exception) {
                concurrency.release()
                return ExecutionResult.fail("Shell process unavailable: ${e.message}", errorCode = ErrorCodes.ERR_IO)
            }
            val result = try {
                withTimeout(commandTimeoutMs) {
                    runInSession(session, commandLine, ctx)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                session.kill()  // 超时销毁（修复泄漏缺口: 取消路径必须销毁子进程）
                ExecutionResult.fail("Command timed out (${commandTimeoutMs / 1000}s)", errorCode = ErrorCodes.ERR_TIMEOUT)
            } catch (e: kotlinx.coroutines.CancellationException) {
                session.kill()  // 用户取消 — 保持取消契约, 不吞
                throw e
            } catch (e: Exception) {
                session.kill()
                KernelLog.w("SessionShellPool", "Error: ${e.message}")
                ExecutionResult.fail(e.message ?: "Unknown error", errorCode = ErrorCodes.ERR_IO)
            }
            // 空闲进程死亡（Android 回收）→ ERR_IO 重试一次（新建会话, 幂等命令）
            if (result.errorCode == ErrorCodes.ERR_IO && attempt == 0) { attempt++; continue }
            concurrency.release()
            return result
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
        // 目录不存在时回退进程默认 cwd — 避免 CreateProcess error=267 (目录名无效)
        try {
            val base = File(com.mengpaw.kernel.DataPaths.BASE)
            if (base.exists()) pb.directory(base)
        } catch (_: Exception) {}
        val proc = pb.start()
        return ShellSession(proc)
    }

    /** 会话内执行（轮询读 — delay 让 withTimeout 取消可中断阻塞读）。 */
    private suspend fun runInSession(
        session: ShellSession, commandLine: String, ctx: ExecutionContext
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        var truncated = false
        try {
            // ── 每次调用自动初始化: 重置 cwd（单引号转义防注入/路径破坏）──
            session.writer.write("cd ${shellEscape(ctx.workDir)} 2>/dev/null\n")
            session.writer.write(commandLine)
            session.writer.write("\n")
            // 退出码捕获: exit_code 紧跟命令（$? 语义）; 哨兵用 printf 前置换行 —
            // 命令输出无结尾换行（如 printf hello）时 echo 会与输出合并同行导致
            // startsWith 不命中 → 误报失败丢输出（P1 修复）
            val marker = "__MENGPAW_DONE_${java.util.UUID.randomUUID().toString().take(8)}__"
            session.writer.write("exit_code=\$?\n")
            session.writer.write("printf '\\n%s\\n' \"$marker\$exit_code\"\n")
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
                    if (sb.length + line.length + 1 <= MAX_OUTPUT) {
                        sb.append(line).append('\n')
                    } else {
                        truncated = true  // 超限: 停止追加, 继续读到哨兵（防写阻塞）
                    }
                }
                if (!done) delay(10)
            }
            returnSession(session)
            var output = sb.toString().trimEnd()
            if (truncated) output = output.take(MAX_OUTPUT - 40) + "\n... (truncated at 100 KB)"
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            session.kill()
            throw e  // 保持取消契约（延迟取消经轮询 delay 抛出）
        } catch (e: Exception) {
            // 写失败（进程死亡/管道断）→ 销毁，下次 borrow 自动重建
            session.kill()
            KernelLog.w("SessionShellPool", "runInSession: ${e.message}")
            ExecutionResult.fail("Shell execution error: ${e.message ?: e::class.simpleName}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    /** 单引号 shell 转义 — 引号内字符全部字面，防路径注入（P2 修复）。 */
    private fun shellEscape(s: String): String = "'" + s.replace("'", "'\\''") + "'"

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
