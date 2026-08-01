// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.tavily

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.io.File

/**
 * Tavily AI Search plugin — provides tavily.* CLI commands.
 *
 * Tavily is an AI-optimized search API that returns structured results
 * instead of HTML pages. Ideal for Agent-driven research.
 *
 * API key 配置: `tavily.setup <key>` 写入 `{BASE}/配置/tavily.json` (DataPaths.CONFIG),
 * 优先级 env `TAVILY_API_KEY` > 配置文件。
 */
class TavilyPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "tavily-plugin", name = "Tavily AI 搜索", version = "0.20.2",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "AI 优化搜索引擎：结构化搜索结果 + 网页内容提取",
        minCoreVersion = "0.2.0",
        commands = listOf("tavily.search", "tavily.extract", "tavily.setup")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "search" to ::search,
        "extract" to ::extract,
        "setup" to ::setup
    )

    private val client = HttpClient(OkHttp) {
        engine { config { connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS) } }
    }

    private val configFile: File get() = File(com.mengpaw.kernel.DataPaths.CONFIG, "tavily.json")

    /** API key: env 优先, 其次配置文件 (tavily.setup 写入)。 */
    private val apiKey: String get() =
        System.getenv("TAVILY_API_KEY")?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val obj = Json.parseToJsonElement(configFile.readText()).jsonObject
                obj["apiKey"]?.jsonPrimitive?.content.orEmpty()
            }.getOrDefault("")

    private val keyError: String
        get() = "Tavily API key 未配置。用 `tavily.setup <key>` 写入配置 (或设置环境变量 TAVILY_API_KEY)。"

    // ── tavily.setup ────────────────────────────────────────────────────

    private suspend fun setup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val key = args.firstOrNull()?.trim()
        return if (key.isNullOrBlank()) {
            val configured = apiKey.isNotBlank()
            ExecutionResult.ok(
                if (configured) "Tavily API key 已配置 (${apiKey.take(4)}...${apiKey.takeLast(4)})\n用 `tavily.setup <新key>` 更新, 传空串清除。"
                else "Tavily API key 未配置。用法: `tavily.setup <key>`"
            )
        } else {
            try {
                configFile.parentFile.mkdirs()
                configFile.writeText(buildJsonObject { put("apiKey", key) }.toString())
                ExecutionResult.ok("Tavily API key 已保存到 ${configFile.absolutePath} (${key.take(4)}...${key.takeLast(4)})")
            } catch (e: Exception) {
                ExecutionResult.fail("保存失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
            }
        }
    }

    private suspend fun search(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (apiKey.isBlank()) return ExecutionResult.fail(keyError, errorCode = ErrorCodes.ERR_INTERNAL)
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
        if (apiKey.isBlank()) return ExecutionResult.fail(keyError, errorCode = ErrorCodes.ERR_INTERNAL)
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
