// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.translate

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Translate plugin — provides translate.* CLI commands.
 *
 * ## Two modes (automatic fallback)
 * **Free mode (default):** Uses Google's public web endpoint — zero setup, always available.
 * **Pro mode:** Uses official Cloud Translation API v2 when GOOGLE_TRANSLATE_API_KEY is set.
 * The plugin auto-detects which mode to use.
 *
 * ## Features
 * - 130+ languages
 * - Auto-detect source language
 * - 500K chars/month free via official API; unlimited via public endpoint
 */
class TranslatePlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "translate-plugin",
        name = "Google 翻译",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Google 翻译 — 支持 130+ 语言，免费免设置",
        minCoreVersion = "0.2.0",
        commands = listOf("translate.text", "translate.auto", "translate.langs", "translate.setup")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "text" to ::translateText,
        "auto" to ::translateAuto,
        "langs" to ::listLanguages,
        "setup" to ::setupGuide
    )

    private val client = HttpClient(OkHttp) {
        engine { config { connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS) } }
    }

    private val hasApiKey: Boolean
        get() = System.getenv("GOOGLE_TRANSLATE_API_KEY")?.isNotBlank() == true

    // ── translate.text ─────────────────────────────────────────────

    private suspend fun translateText(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: translate.text <content> [--from=<lang>] [--to=<lang>]\nExample: translate.text Hello World --to=zh",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val flags = args.filter { it.startsWith("--") }
        val to = flags.find { it.startsWith("--to=") }?.removePrefix("--to=") ?: systemLang()
        val from = flags.find { it.startsWith("--from=") }?.removePrefix("--from=")
        val text = args.filter { !it.startsWith("--") }.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("No text to translate.", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        return if (hasApiKey) translateWithApi(text, from, to) else translateFree(text, from, to)
    }

    // ── translate.auto ─────────────────────────────────────────────

    private suspend fun translateAuto(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: translate.auto <content>\nAuto-detects source language → system language.",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val text = args.joinToString(" ")
        val to = systemLang()

        return if (hasApiKey) {
            // Detect + translate via official API
            try {
                val key = System.getenv("GOOGLE_TRANSLATE_API_KEY") ?: ""
                val detectBody = """{"q":"$text"}"""
                val detectResp = client.post("https://translation.googleapis.com/language/translate/v2/detect?key=$key") {
                    contentType(ContentType.Application.Json); setBody(detectBody)
                }
                val from = parseDetectedLang(detectResp.body<String>())
                val resp = translateWithApi(text, from, to)
                val translated = resp.output
                if (resp.success) ExecutionResult.ok("[$from → $to] $translated") else resp
            } catch (e: Exception) {
                translateFree(text, "auto", to) // fallback to free
            }
        } else {
            translateFree(text, "auto", to)
        }
    }

    // ── translate.langs ────────────────────────────────────────────

    private suspend fun listLanguages(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val mode = if (hasApiKey) "Pro (Cloud API)" else "Free (public endpoint)"
        return ExecutionResult.ok("""
## Google 翻译 — 语言代码 ($mode)

| 语言 | 代码 |     | 语言 | 代码 |
|------|------|-----|------|------|
| 中文 (简体) | zh-CN | | 中文 (繁体) | zh-TW |
| 英文 | en |     | 日文 | ja |
| 韩文 | ko |     | 法文 | fr |
| 德文 | de |     | 西班牙文 | es |
| 葡萄牙文 | pt |     | 意大利文 | it |
| 俄文 | ru |     | 阿拉伯文 | ar |
| 印地文 | hi |     | 泰文 | th |
| 越南文 | vi |     | 印尼文 | id |
| 马来文 | ms |     | 菲律宾文 | fil |
| 土耳其文 | tr |     | 荷兰文 | nl |
| 波兰文 | pl |     | 乌克兰文 | uk |
| 瑞典文 | sv |     | 挪威文 | no |
| 丹麦文 | da |     | 芬兰文 | fi |
| 希腊文 | el |     | 希伯来文 | he |

完整: https://cloud.google.com/translate/docs/languages

用法:
  translate.text Hello --to=ja    # → こんにちは
  translate.auto 你好世界          # → Hello World (自动检测)
        """.trimIndent())
    }

    // ── translate.setup ────────────────────────────────────────────

    private suspend fun setupGuide(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
## Google 翻译 — 配置指南

### 当前模式: ${if (hasApiKey) "Pro (Cloud API Key 已配置)" else "Free (公开端点，无需设置)"}

### Free 模式（默认，推荐）
开箱即用 — 无需任何设置。
Agent 可以直接使用 translate.text / translate.auto 翻译文本。

### Pro 模式（可选，更高稳定性）
如需官方 SLA 保证和更高并发上限:
1. 打开 https://console.cloud.google.com
2. 创建项目 → 启用 Cloud Translation API → 创建 API Key
3. 设置环境变量: export GOOGLE_TRANSLATE_API_KEY=AIza...
4. 验证: translate.text Hello --to=zh

### 费用对比
- Free 模式: 完全免费，无需账号
- Pro 模式: 每月 500,000 字符免费，超出 $20/百万字符
        """.trimIndent())
    }

    // ── Translation engines ────────────────────────────────────────

    /**
     * Free translation using Google's public web endpoint.
     * Same backend as translate.google.com — no API key needed.
     */
    private suspend fun translateFree(text: String, from: String?, to: String): ExecutionResult {
        return try {
            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
            val sl = from ?: "auto"
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$to&dt=t&q=$encoded"
            val resp: HttpResponse = client.get(url) {
                headers { append("User-Agent", "Mozilla/5.0") }
            }
            val body: String = resp.body<String>()
            val translated = parseFreeResponse(body)
            val prefix = if (from == null || from == "auto") "[auto→$to] " else "[$from→$to] "
            ExecutionResult.ok(prefix + translated)
        } catch (e: Exception) {
            ExecutionResult.fail("Translation error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /**
     * Official Cloud Translation API v2 (requires API key).
     */
    private suspend fun translateWithApi(text: String, from: String?, to: String): ExecutionResult {
        val key = System.getenv("GOOGLE_TRANSLATE_API_KEY") ?: ""
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("q", kotlinx.serialization.json.JsonPrimitive(text))
                put("target", kotlinx.serialization.json.JsonPrimitive(to))
                put("format", kotlinx.serialization.json.JsonPrimitive("text"))
                if (from != null) put("source", kotlinx.serialization.json.JsonPrimitive(from))
            }
            val resp = client.post("https://translation.googleapis.com/language/translate/v2?key=$key") {
                contentType(ContentType.Application.Json); setBody(body.toString())
            }
            parseApiResponse(resp.body<String>(), to)
        } catch (e: Exception) {
            ExecutionResult.fail("API error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── Parsers ────────────────────────────────────────────────────

    /** Parse free endpoint response: [[["text","orig",...]],null,"en"] */
    private fun parseFreeResponse(json: String): String {
        val sb = StringBuilder()
        val re = Regex("""\[\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"""")
        re.findAll(json).forEach { m ->
            sb.append(m.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\"))
        }
        return sb.toString().ifBlank { "(empty)" }
    }

    /** Parse official API v2 response. */
    private fun parseApiResponse(jsonStr: String, target: String): ExecutionResult {
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject ?:
                return ExecutionResult.fail("Invalid JSON", errorCode = ErrorCodes.ERR_INTERNAL)
            val data = root["data"] as? kotlinx.serialization.json.JsonObject
            val translations = data?.get("translations") as? kotlinx.serialization.json.JsonArray
            val first = translations?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            val translated = (first?.get("translatedText") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return ExecutionResult.fail("No translatedText in response", errorCode = ErrorCodes.ERR_INTERNAL)
            val detected = (first?.get("detectedSourceLanguage") as? kotlinx.serialization.json.JsonPrimitive)?.content
            val prefix = if (detected != null) "[$detected→$target] " else ""
            ExecutionResult.ok(prefix + translated)
        } catch (e: Exception) {
            ExecutionResult.fail("Parse error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** Parse detect-language response. */
    private fun parseDetectedLang(jsonStr: String): String {
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
            val data = root?.get("data") as? kotlinx.serialization.json.JsonObject
            val detections = data?.get("detections") as? kotlinx.serialization.json.JsonArray
            val first = detections?.firstOrNull() as? kotlinx.serialization.json.JsonArray
            val entry = first?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            (entry?.get("language") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "auto"
        } catch (e: Exception) { "auto" }
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** Best-effort system language. */
    private fun systemLang(): String = try {
        java.util.Locale.getDefault().language.let { if (it.length == 2) it else "zh" }
    } catch (e: Exception) { "zh" }
}
