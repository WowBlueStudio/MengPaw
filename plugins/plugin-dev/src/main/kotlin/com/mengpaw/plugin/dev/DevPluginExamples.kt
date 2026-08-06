// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.dev

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * DevPlugin 示例参考 — 从 DevPlugin 拆出 (无状态, 顶层函数)。
 *
 * plugin.examples 命令: 返回文件操作与网络请求插件模板。
 */
internal suspend fun examplesCommand(args: List<String>, ctx: ExecutionContext): ExecutionResult {
    return ExecutionResult.ok("""
=== MengPaw 插件开发参考 ===

【文件操作插件模板】(参考 fs-plugin)

import com.mengpaw.kernel.cli.*
import com.mengpaw.kernel.plugin.*
import java.io.File

class MyFsPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "my-fs-plugin", name = "我的文件插件", version = "0.1.0",
        type = PluginType.NATIVE, author = "Agent-自己",
        description = "文件操作：read, list",
        commands = listOf("myfs.read", "myfs.list")
    )
    override val commands: Map<String, CommandHandler> = mapOf(
        "read" to ::read, "list" to ::list
    )

    private suspend fun read(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: myfs read <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = resolvePath(args[0], ctx)
        val file = File(path)
        if (!file.exists()) return ExecutionResult.fail("Not found: ${'$'}path", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok(try { file.readText() } catch (e: Exception) { "读取失败: ${'$'}{e.message}" })
    }

    private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = if (args.isNotEmpty()) resolvePath(args[0], ctx) else ctx.workDir
        val dir = File(path)
        if (!dir.isDirectory) return ExecutionResult.fail("Not a directory", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val listing = dir.listFiles()?.joinToString("\n") { f ->
            "${'$'}{if (f.isDirectory) "d" else "-"} ${'$'}{f.name} (${'$'}{formatSize(f.length())})"
        } ?: ""
        return ExecutionResult.ok(listing.ifEmpty { "(empty)" })
    }

    private fun resolvePath(path: String, ctx: ExecutionContext): String {
        val file = File(path)
        return if (file.isAbsolute) file.absolutePath else File(ctx.workDir, path).absolutePath
    }
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${'$'}bytes B"
        bytes < 1024*1024 -> "%.1f KB".format(bytes/1024.0)
        else -> "%.1f MB".format(bytes/(1024.0*1024.0))
    }
}

【网络请求插件模板】(参考 net-plugin)

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.util.concurrent.TimeUnit

class MyNetPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "my-net-plugin", name = "我的网络插件", version = "0.1.0",
        type = PluginType.NATIVE, author = "Agent-自己",
        description = "HTTP 请求：get, post",
        permissions = listOf("INTERNET"),
        commands = listOf("mynet.get", "mynet.post")
    )
    private val client = HttpClient(OkHttp) {
        engine { config { connectTimeout(10, TimeUnit.SECONDS); readTimeout(30, TimeUnit.SECONDS) } }
    }
    override val commands: Map<String, CommandHandler> = mapOf(
        "get" to ::get, "post" to ::post
    )
    private suspend fun get(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: mynet get <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return try {
            ExecutionResult.ok(client.get(args[0]).bodyAsText().take(10000))
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${'$'}{e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
    private suspend fun post(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: mynet post <url> <body>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return try {
            ExecutionResult.ok(client.post(args[0]) { setBody(args.drop(1).joinToString(" ")) }.bodyAsText().take(10000))
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${'$'}{e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}

【关键模式】
- resolvePath() — 处理相对/绝对路径，ctx.workDir 为当前工作目录
- ErrorCodes.ERR_INVALID_INPUT / ERR_NOT_FOUND / ERR_PERMISSION_DENIED / ERR_INTERNAL — 标准错误码
- formatSize() — 字节→人类可读
- HttpClient — Ktor OkHttp 引擎，10s 连接超时 + 30s 读取超时
- 所有文件 IO 必须 try/catch
- 所有网络请求必须 try/catch
    """.trimIndent())
}
