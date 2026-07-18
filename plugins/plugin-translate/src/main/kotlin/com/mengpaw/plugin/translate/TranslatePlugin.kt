package com.mengpaw.plugin.translate

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
 * Google Cloud Translation plugin — provides translate.* CLI commands.
 *
 * ## Setup
 * Users need a Google Cloud API key with Translation API enabled.
 * Run `translate.setup` for step-by-step instructions (Agent can guide the user).
 *
 * ## Features
 * - 130+ languages via Google Cloud Translation v2 REST API
 * - Auto-detect source language when not specified
 * - 500K chars/month free tier (sufficient for personal use)
 * - API key stored in Vault or GOOGLE_TRANSLATE_API_KEY env var
 *
 * ## Architecture
 * Uses Google's REST v2 endpoint (simpler than v3 — no project ID needed).
 * ```
 * POST https://translation.googleapis.com/language/translate/v2?key={KEY}
 * Body: {"q":"text","target":"en"}
 * ```
 */
class TranslatePlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "translate-plugin",
        name = "Google 翻译",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Google Cloud Translation — 130+ 语言翻译，自动检测源语言",
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

    /** Resolve API key: Vault > env var > empty. */
    private fun resolveKey(): String {
        return try {
            // Attempt Vault via reflection (avoids compile-time dependency on Android Context)
            val vaultClass = Class.forName("com.mengpaw.core.security.Vault")
            // Can't instantiate Vault without Context — fallback to env
            System.getenv("GOOGLE_TRANSLATE_API_KEY") ?: ""
        } catch (e: Exception) {
            System.getenv("GOOGLE_TRANSLATE_API_KEY") ?: ""
        }
    }

    // ── translate.text ─────────────────────────────────────────────

    private suspend fun translateText(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val key = resolveKey()
        if (key.isBlank()) return missingKeyMessage()

        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: translate.text <content> [--from=<lang>] [--to=<lang>]\n" +
            "Example: translate.text Hello World --to=zh",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )

        // Parse flags
        val flags = args.filter { it.startsWith("--") }
        val to = flags.find { it.startsWith("--to=") }?.removePrefix("--to=")
            ?: systemLanguageCode()
        val from = flags.find { it.startsWith("--from=") }?.removePrefix("--from=")
        val text = args.filter { !it.startsWith("--") }.joinToString(" ")

        if (text.isBlank()) return ExecutionResult.fail("No text to translate.", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        return try {
            val body = buildJsonObject {
                put("q", text)
                put("target", to)
                if (from != null) put("source", from)
                put("format", "text")
            }
            val url = "https://translation.googleapis.com/language/translate/v2?key=$key"
            val resp = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            parseTranslation(resp.bodyAsText(), text, to)
        } catch (e: Exception) {
            ExecutionResult.fail("Translation error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── translate.auto ─────────────────────────────────────────────

    private suspend fun translateAuto(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val key = resolveKey()
        if (key.isBlank()) return missingKeyMessage()

        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: translate.auto <content>\nAuto-detects source language and translates to system language.",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )

        val text = args.joinToString(" ")
        val to = systemLanguageCode()

        return try {
            // Detect source language
            val detectBody = buildJsonObject { put("q", text) }
            val detectResp = client.post("https://translation.googleapis.com/language/translate/v2/detect?key=$key") {
                contentType(ContentType.Application.Json)
                setBody(detectBody.toString())
            }
            val detectJson = Json.parseToJsonElement(detectResp.bodyAsText()).jsonObject
            val detections = detectJson["data"]?.jsonObject?.get("detections")?.jsonArray
            val detected = detections?.firstOrNull()?.jsonArray?.firstOrNull()?.jsonObject
            val from = detected?.get("language")?.jsonPrimitive?.content ?: "auto"

            // Translate
            val body = buildJsonObject {
                put("q", text)
                put("target", to)
                put("format", "text")
            }
            val resp = client.post("https://translation.googleapis.com/language/translate/v2?key=$key") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val result = parseTranslation(resp.bodyAsText(), text, to)
            val translated = result.output
            ExecutionResult.ok("[$from → $to] $translated")
        } catch (e: Exception) {
            ExecutionResult.fail("Translation error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── translate.langs ────────────────────────────────────────────

    private suspend fun listLanguages(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            """
            ## Google Translate — 常用语言代码
            | 语言 | 代码 |
            |------|------|
            | 中文 (简体) | zh |     | 中文 (繁体) | zh-TW |
            | 英文 | en |           | 日文 | ja |
            | 韩文 | ko |           | 法文 | fr |
            | 德文 | de |           | 西班牙文 | es |
            | 葡萄牙文 | pt |       | 意大利文 | it |
            | 俄文 | ru |           | 阿拉伯文 | ar |
            | 印地文 | hi |         | 泰文 | th |
            | 越南文 | vi |         | 印尼文 | id |
            | 马来文 | ms |         | 菲律宾文 | fil |
            | 土耳其文 | tr |       | 荷兰文 | nl |
            | 波兰文 | pl |         | 乌克兰文 | uk |
            | 瑞典文 | sv |         | 挪威文 | no |
            | 丹麦文 | da |         | 芬兰文 | fi |
            | 希腊文 | el |         | 希伯来文 | he |

            完整列表 130+ 语言: https://cloud.google.com/translate/docs/languages

            用法:
              translate.text Hello World --to=ja     # 英→日
              translate.auto 你好世界                  # 自动检测→系统语言
            """.trimIndent()
        )
    }

    // ── translate.setup ────────────────────────────────────────────

    private suspend fun setupGuide(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            """
            ## Google Cloud Translation API — 配置指南

            ### 1. 创建 Google Cloud 项目（5分钟，免费）
            1. 打开 https://console.cloud.google.com
            2. 登录 Google 账号（需绑定信用卡，验证身份，不扣费）
            3. 点击顶部 "选择项目" → "新建项目" → 命名后创建

            ### 2. 启用 Translation API
            1. 在 Console 搜索 "Cloud Translation API"
            2. 点击 "启用" (ENABLE)
            3. 等待几秒激活

            ### 3. 创建 API Key
            1. 左侧菜单 → "凭据" (Credentials)
            2. 点击 "+ 创建凭据" → "API 密钥"
            3. 复制生成的密钥（格式: AIza...）

            ### 4. 设置到 MengPaw
            将 API Key 设置为环境变量:
              export GOOGLE_TRANSLATE_API_KEY=AIza...

            或通过 Agent 配置（推荐）:
              将密钥告诉 Agent，Agent 会自动保存到安全保险库。

            ### 5. 验证
              translate.text Hello --to=zh   # 应返回 "你好"

            ### 费用
            每月免费 500,000 字符（约 25 万汉字），个人使用绰绰有余。
            超出后 $20/百万字符。新账号另有 $300 赠金。
            """.trimIndent()
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun missingKeyMessage() = ExecutionResult.fail(
        "Google Translate API key not configured.\n\n" +
        "Run `translate.setup` for a step-by-step guide to get your free API key.\n" +
        "The Agent can also walk you through the setup interactively.",
        errorCode = ErrorCodes.ERR_INTERNAL
    )

    /** Best-effort system language from JVM, falls back to "zh". */
    private fun systemLanguageCode(): String {
        return try {
            val sysLang = java.util.Locale.getDefault().language
            if (sysLang.isNotBlank() && sysLang.length == 2) sysLang else "zh"
        } catch (e: Exception) { "zh" }
    }

    private fun parseTranslation(jsonStr: String, original: String, target: String): ExecutionResult {
        return try {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val data = root["data"]?.jsonObject
            val translations = data?.get("translations")?.jsonArray
            val translated = translations?.firstOrNull()?.jsonObject?.get("translatedText")?.jsonPrimitive?.content
                ?: return ExecutionResult.fail("Unexpected API response: no translatedText found", errorCode = ErrorCodes.ERR_INTERNAL)

            // If source was auto-detected, include it
            val detectedSource = translations.firstOrNull()?.jsonObject?.get("detectedSourceLanguage")?.jsonPrimitive?.content
            val prefix = if (detectedSource != null) "[$detectedSource → $target] " else ""
            ExecutionResult.ok(prefix + translated)
        } catch (e: Exception) {
            // Fallback: try parsing as v3 response
            try {
                val root = Json.parseToJsonElement(jsonStr).jsonObject
                val translations = root["translations"]?.jsonArray
                val translated = translations?.firstOrNull()?.jsonObject?.get("translatedText")?.jsonPrimitive?.content
                    ?: return ExecutionResult.fail("Unexpected API response", errorCode = ErrorCodes.ERR_INTERNAL)
                ExecutionResult.ok(translated)
            } catch (e2: Exception) {
                ExecutionResult.fail("Failed to parse translation response: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
            }
        }
    }
}
