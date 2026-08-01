// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.common

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 远程命令执行结果 (exitCode=0 视为成功, stdout 为命令输出). */
data class ExecResult(val exitCode: Int, val stdout: String)

/**
 * 交互式远程进程通道 (shell channel) — 用于需要长驻 stdin/stdout 流的协议
 * (如 QwenPaw ACP stdio JSON-RPC: 请求写行, 响应读行)。
 * 后台读取线程把远程输出逐行入队, [readLine] 带超时消费。
 */
class InteractiveChannel(
    private val channel: Channel,
    private val out: OutputStream,
    private val queue: LinkedBlockingQueue<String>,
    private val readerThread: Thread
) {
    /** 写一行到远程进程 stdin (newline-delimited 协议)。 */
    fun writeLine(line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 读一行远程输出, 超时返回 null。 */
    fun readLine(timeoutMs: Long = 30_000): String? = try {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        null
    }

    fun close() {
        try { channel.disconnect() } catch (_: Exception) {}
        readerThread.interrupt()
    }
}

/**
 * SSH 传输封装 (jsch, MIT 许可) — 手机连接器到 PC 执行 CLI 的通用通道。
 *
 * - 密码或 PEM 密钥认证; 个人局域网场景, StrictHostKeyChecking 关闭 (信任局域网)
 * - exec 每次命令独立 channel, 超时自动断开, 永不泄漏 channel
 * - 目标 PC 默认 shell 为 cmd.exe (Windows OpenSSH Server): 命令按 cmd 语法
 *   拼接, 提示词内嵌双引号替换为单引号 (cmd 无转义引号, 中文场景可接受)
 *
 * 用法: connect() 一次 → 多次 exec → disconnect() 释放。
 */
class SshTransport(
    private val host: String,
    private val port: Int = 22,
    private val user: String,
    private val password: String? = null,
    private val keyPath: String? = null,
    private val keyPassphrase: String? = null
) {
    @Volatile private var session: Session? = null

    fun connect(timeoutMs: Int = 15_000): Result<Unit> = try {
        val jsch = JSch()
        if (!keyPath.isNullOrBlank()) jsch.addIdentity(keyPath, keyPassphrase ?: "")
        val s = jsch.getSession(user, host, port)
        if (!password.isNullOrBlank()) s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig(
            "PreferredAuthentications",
            if (password.isNullOrBlank()) "publickey" else "password,keyboard-interactive,publickey"
        )
        s.timeout = timeoutMs
        s.connect(timeoutMs)
        session = s
        Result.success(Unit)
    } catch (e: Exception) {
        session = null
        Result.failure(
            IllegalStateException(
                "${e.message ?: "SSH 连接失败"} — 若首次启用 OpenSSH Server 请确认 Windows 防火墙已放行 TCP ${port} 端口"
            )
        )
    }

    fun isConnected(): Boolean = session?.isConnected == true

    /**
     * 执行一条命令并等待退出 (默认 120s 超时)。
     * exitCode != 0 时返回失败, 错误信息含 stderr 摘要。
     */
    fun exec(command: String, timeoutMs: Long = 120_000): Result<ExecResult> {
        val s = session ?: return Result.failure(IllegalStateException("SSH 未连接 — 先执行 connect"))
        val ch = s.openChannel("exec") as ChannelExec
        return try {
            ch.setCommand(command)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            // jsch: getErrStream() 返回 InputStream 与 setErrStream(OutputStream) 类型不匹配,
            // Kotlin 只合成只读属性 — 必须用 Java 方法形式调用 setter
            ch.setOutputStream(out)
            ch.setErrStream(err)
            ch.connect(15_000)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!ch.isClosed) {
                if (System.currentTimeMillis() > deadline) {
                    ch.disconnect()
                    return Result.failure(IllegalStateException("命令超时 (>${timeoutMs / 1000}s): $command"))
                }
                Thread.sleep(100)
            }
            val stdout = out.toString(Charsets.UTF_8)
            val stderr = err.toString(Charsets.UTF_8)
            val exit = ch.exitStatus
            if (exit == 0) Result.success(ExecResult(exit, stdout))
            else Result.failure(
                IllegalStateException("exit $exit: ${(if (stderr.isNotBlank()) stderr else stdout).take(500)}")
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            ch.disconnect()
        }
    }

    /**
     * 打开交互式进程通道 — 在远程启动长驻命令 (如 `qwenpaw acp`) 并返回
     * [InteractiveChannel] 做 newline-delimited 双向 IO。关闭后通道不可复用。
     * 用 exec channel (ChannelExec) 承载: stdin 写流 = getOutputStream(),
     * stdout 读流 = getInputStream()。
     */
    fun openInteractive(command: String): Result<InteractiveChannel> = try {
        val s = session ?: return Result.failure(IllegalStateException("SSH 未连接 — 先执行 connect"))
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val remoteOut = ch.inputStream
        val localOut = ch.outputStream
        ch.connect(15_000)
        val reader = BufferedReader(InputStreamReader(remoteOut, Charsets.UTF_8))
        val queue = LinkedBlockingQueue<String>()
        val readerThread = Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    queue.offer(line)
                }
            } catch (_: Exception) {}
        }
        readerThread.isDaemon = true
        readerThread.start()
        Result.success(InteractiveChannel(ch, localOut, queue, readerThread))
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun disconnect() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    companion object {
        /**
         * cmd.exe 兼容的命令参数转义 — 防注入。
         * 换行/回车替换为空格; 双引号替换为单引号 (cmd 不支持 \" 转义)。
         */
        fun shellEscape(s: String): String =
            s.replace("\r", " ").replace("\n", " ").replace("\"", "'")
    }
}
