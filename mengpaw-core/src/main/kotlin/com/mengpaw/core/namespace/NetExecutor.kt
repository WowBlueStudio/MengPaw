package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

/**
 * Network operations namespace.
 */
object NetExecutor {
    val commands = mapOf(
        "curl" to ::curl,
        "get" to ::get,
        "post" to ::post
    )

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private suspend fun curl(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: net curl <url>")
        val url = args[0]
        return try {
            val response: HttpResponse = client.get(url)
            val body = response.bodyAsText()
            ExecutionResult.ok(body.take(10000))
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${e.message}")
        }
    }

    private suspend fun get(args: List<String>, ctx: ExecutionContext): ExecutionResult = curl(args, ctx)

    private suspend fun post(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: net post <url> <body>")
        val url = args[0]
        val body = args.drop(1).joinToString(" ")
        return try {
            val response: HttpResponse = client.post(url) {
                setBody(body)
            }
            val responseBody = response.bodyAsText()
            ExecutionResult.ok(responseBody.take(10000))
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${e.message}")
        }
    }
}
