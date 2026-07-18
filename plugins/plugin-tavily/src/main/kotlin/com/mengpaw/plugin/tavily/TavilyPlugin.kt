// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.tavily

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
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Tavily AI Search plugin — provides tavily.* CLI commands.
 *
 * Tavily is an AI-optimized search API that returns structured results
 * instead of HTML pages. Ideal for Agent-driven research.
 *
 * Requires TAVILY_API_KEY in environment or Vault.
 */
class TavilyPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "tavily-plugin", name = "Tavily AI 搜索", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "AI 优化搜索引擎：结构化搜索结果 + 网页内容提取",
        minCoreVersion = "0.2.0",
        commands = listOf("tavily.search", "tavily.extract")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "search" to ::search,
        "extract" to ::extract
    )

    private val client = HttpClient(OkHttp) {
        engine { config { connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS) } }
    }

    private val apiKey: String get() = System.getenv("TAVILY_API_KEY") ?: ""

    private suspend fun search(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (apiKey.isBlank()) return ExecutionResult.fail(
            "TAVILY_API_KEY not set. Export it in your environment or Vault.",
            errorCode = ErrorCodes.ERR_INTERNAL)
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tavily.search <query> [--max=5]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val query = args.takeWhile { !it.startsWith("--") }.joinToString(" ")
        val maxResults = args.find { it.startsWith("--max=") }?.removePrefix("--max=")?.toIntOrNull() ?: 5

        return try {
            val body = buildJsonObject {
                put("api_key", apiKey)
                put("query", query)
                put("max_results", maxResults)
                put("include_answer", true)
            }
            val resp = client.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val answer = json["answer"]?.jsonPrimitive?.content ?: ""
            val results = json["results"]?.jsonArray ?: emptyList()
            val out = buildString {
                if (answer.isNotBlank()) { appendLine("## AI 摘要"); appendLine(answer); appendLine() }
                appendLine("## 搜索结果 (${results.size})")
                results.take(maxResults).forEachIndexed { i, r ->
                    val obj = r.jsonObject
                    appendLine("${i+1}. **${obj["title"]?.jsonPrimitive?.content ?: ""}**")
                    appendLine("   ${obj["url"]?.jsonPrimitive?.content ?: ""}")
                    appendLine("   ${(obj["content"]?.jsonPrimitive?.content ?: "").take(200)}")
                    appendLine()
                }
            }
            ExecutionResult.ok(out)
        } catch (e: Exception) {
            ExecutionResult.fail("Tavily error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun extract(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (apiKey.isBlank()) return ExecutionResult.fail(
            "TAVILY_API_KEY not set.", errorCode = ErrorCodes.ERR_INTERNAL)
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tavily.extract <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        return try {
            val body = buildJsonObject {
                put("api_key", apiKey)
                put("urls", buildJsonArray { add(args[0]) })
            }
            val resp = client.post("https://api.tavily.com/extract") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val results = json["results"]?.jsonArray ?: emptyList()
            val content = results.firstOrNull()?.jsonObject?.get("raw_content")?.jsonPrimitive?.content
                ?: "(no content extracted)"
            ExecutionResult.ok(content.take(8000))
        } catch (e: Exception) {
            ExecutionResult.fail("Tavily extract error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
