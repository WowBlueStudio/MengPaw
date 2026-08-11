// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.mengpaw.browser.plugin.BuiltinBrowserPlugin
import com.mengpaw.browser.util.BrowserStorage
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Termux 式 am 桥执行服务（半自动武器方案 Phase 2）。
 *
 * Shell 子进程经 `am startservice` 调用（signature 权限
 * `com.mengpaw.permission.RUN_BROWSER_COMMAND`，仅同签名 Shell 可调）。
 * 执行走内置命令集（page.* / browser.*），输出落盘到调用方指定路径
 * （限制在公共目录 MengPaw/ 下），Shell 用 agent.read / Linux 管道读回。
 *
 * intent extras（见方案 §六）:
 * - [EXTRA_ARGUMENTS]: `-c,<命令串>`（引号包裹）
 * - [EXTRA_OUTPUT]: 输出文件路径（必须位于公共目录下）
 * - [EXTRA_BACKGROUND]: 后台执行（默认 true，忽略前台通知要求）
 */
class RunCommandService : Service() {

    companion object {
        const val ACTION_RUN_COMMAND = "com.mengpaw.browser.RUN_COMMAND"
        const val EXTRA_ARGUMENTS = "com.mengpaw.browser.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_OUTPUT = "com.mengpaw.browser.RUN_COMMAND_OUTPUT"
        const val EXTRA_BACKGROUND = "com.mengpaw.browser.RUN_COMMAND_BACKGROUND"

        /** 浏览器命令前缀白名单（纵深防御 — CommandMonitor 已校验，服务侧再校验一次）。 */
        private val COMMAND_PREFIXES = listOf("page.", "browser.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val line = intent?.getStringExtra(EXTRA_ARGUMENTS)
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT)
        if (line.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread {
            try {
                val result = executeCommandLine(line)
                if (!outputPath.isNullOrBlank()) {
                    writeOutput(outputPath, result)
                }
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    /** 解析 `-c,<命令串>` 并执行浏览器命令，返回执行输出文本。 */
    private fun executeCommandLine(line: String): String {
        val payload = if (line.startsWith("-c,")) line.removePrefix("-c,") else line
        val tokens = tokenize(payload)
        val cmdName = tokens.firstOrNull() ?: return "空命令"
        // 纵深防御: 只允许浏览器命令集（page.* / browser.*）
        if (COMMAND_PREFIXES.none { cmdName.startsWith(it) }) {
            return "拒绝: 仅允许浏览器命令 (page.* / browser.*): $cmdName"
        }
        val plugin = BuiltinBrowserPlugin.shared
            ?: return "浏览器未就绪（请先打开 MP 浏览器再调用）"
        val handler = plugin.commands[cmdName]
            ?: return "未知浏览器命令: $cmdName（page.* / browser.* 命令集）"
        val ctx = ExecutionContext(sessionId = "am", userId = "am")
        return try {
            val result: ExecutionResult = runBlocking { handler(tokens.drop(1), ctx) }
            if (result.success) result.output else result.error ?: "命令执行失败"
        } catch (e: Exception) {
            "执行异常: ${e.message}"
        }
    }

    /** 引号感知分词（与 CommandMonitor.tokenize 语义对齐，供 am 桥命令串使用）。 */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote: Char? = null
        var escape = false
        for (c in input) {
            when {
                escape -> { cur.append(c); escape = false }
                c == '\\' -> escape = true
                inQuote != null -> {
                    if (c == inQuote) inQuote = null else cur.append(c)
                }
                c == '\'' || c == '"' -> inQuote = c
                c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { tokens.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        return tokens
    }

    /** 输出落盘（决策 §六: 限制公共目录 MengPaw/ 下，禁止系统路径）。 */
    private fun writeOutput(outputPath: String, content: String) {
        try {
            val normalized = outputPath.replace('\\', '/')
            val allowedBase = BrowserStorage.PUBLIC_BASE.replace('\\', '/')
            if (!normalized.startsWith(allowedBase + "/") && normalized != allowedBase) {
                return
            }
            val file = File(normalized)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (_: Exception) {
            // 落盘失败静默 — 调用方读不到时经命令结果自行发现
        }
    }
}
