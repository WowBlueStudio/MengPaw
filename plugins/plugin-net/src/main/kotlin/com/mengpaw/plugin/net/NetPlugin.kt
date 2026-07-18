// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.net

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.util.concurrent.TimeUnit

/**
 * Network plugin — provides net.* CLI commands.
 * Requires Ktor HTTP client.
 */
class NetPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "net-plugin",
        name = "网络请求",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "HTTP 网络请求：curl, get, post",
        permissions = listOf("INTERNET"),
        minCoreVersion = "0.2.0",
        commands = listOf("net.curl", "net.get", "net.post")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "curl" to ::curl,
        "get" to ::curl,    // alias
        "post" to ::post
    )

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    private suspend fun curl(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: net curl <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]
        return try {
            val response = client.get(url)
            val body = response.bodyAsText().take(10000)
            ExecutionResult.ok(body)
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun post(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: net post <url> <body>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]
        val body = args.drop(1).joinToString(" ")
        return try {
            val response = client.post(url) { setBody(body) }
            ExecutionResult.ok(response.bodyAsText().take(10000))
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
