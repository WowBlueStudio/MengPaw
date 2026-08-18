// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
 * API key 配置: `tavily.setup` 写入 `{BASE}/配置/tavily.json` (DataPaths.CONFIG),
 * 优先级 env `TAVILY_API_KEY` > 配置文件。三种 key 来源:
 *   - `tavily.setup <key>`            内联传参 (兼容旧用法 — 但命令原文会进会话历史/审计日志)
 *   - `tavily.setup --from-file <路径>` 从文件首行读取 (推荐 — key 不进入会话历史)
 *   - `tavily.setup --from-clipboard` 插件层无系统剪贴板能力, 明确报错并引导 --from-file
 *
 * ## API key 存储安全 (P2 折中, 2026-08-06)
 * 红线: 插件只依赖 mengpaw-kernel (纯 JVM, 零 Android 依赖) → 无法直接用
 * androidx.security EncryptedSharedPreferences / Android Keystore (那是 mengpaw-core
 * Vault 的能力, 插件不可达)。原实现明文落盘, 目录浏览即得密钥。
 * 折中: 落盘前做轻量混淆 (XOR), 并兼容旧明文配置 (见 apiKey getter)。
 * 注意: 混淆 ≠ 加密 — XOR 密钥常量在 APK 内, 反编译可还原, 仅防"顺目录浏览"级泄露。
 * 根治方案: kernel 增加密钥存储抽象 (桥接 core 的 Vault) 后迁移, 届时删除本折中。
 *
 * ## key 脱敏 (P2-9, 2026-08-06)
 * kernel Pipeline 的审计日志会原样记录命令文本 (仅输出经 Sanitizer 脱敏),
 * 而 kernel Sanitizer 覆盖 sk-/sk-ant-/AIza/bearer/Base64 — 不含 tavily 的 tvly-
 * 前缀 (kernel 冻结不可改)。因此插件层策略:
 *  1. 所有成功/状态消息只回显 key 长度, key 原文仅进存储;
 *  2. 引导用户用 --from-file / --from-clipboard, 让 key 根本不进入命令文本。
 */
class TavilyPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "tavily-plugin", name = "AI 搜索", version = "", // 内置插件, 随 Shell APK 版本更新
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

    companion object {
        /** 混淆密钥 (P2 折中 — 防目录浏览级泄露, 非真加密, 见类头注释)。 */
        private const val OBFUSCATION_KEY = 0x5A

        /** 配置文件路径 — 与实例私有 getter 同源, 供 UI 设置页读写复用。 */
        internal fun configFile(): File = File(com.mengpaw.kernel.DataPaths.CONFIG, "tavily.json")

        /** 轻量混淆 (P2 折中, 见类头注释) — UTF-8 字节 XOR, 防"目录浏览即得明文"。
         *  internal 为测试可见性 (编解码单测)。 */
        internal fun obfuscate(plain: String): String {
            val bytes = plain.toByteArray(Charsets.UTF_8)
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor OBFUSCATION_KEY).toByte()
            return String(bytes, Charsets.ISO_8859_1) // 逐字节映射, 不经 UTF-8 校验
        }

        /** internal 为测试可见性 (编解码单测)。 */
        internal fun deobfuscate(encoded: String): String {
            val bytes = encoded.toByteArray(Charsets.ISO_8859_1)
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor OBFUSCATION_KEY).toByte()
            return String(bytes, Charsets.UTF_8)
        }

        /**
         * 供 Shell 框架设置页调用 — 写 API key 到 tavily.json (XOR 混淆落盘)。
         * key 明文仅作为函数参数进入本调用, 不写日志、不回显; 返回是否成功。
         */
        fun saveApiKeyFromUi(key: String): Boolean = try {
            configFile().parentFile?.mkdirs()
            configFile().writeText(buildJsonObject { put("apiKey", "obf:" + obfuscate(key)) }.toString())
            true
        } catch (e: Exception) {
            false
        }

        /** 供 Shell 框架设置页调用 — API key 是否已配置 (env 优先, 其次配置文件; 均按真实 key 判空)。 */
        fun isApiKeyConfigured(): Boolean =
            System.getenv("TAVILY_API_KEY")?.takeIf { it.isNotBlank() } != null || storedConfiguredKey().isNotEmpty()

        /** 供 Shell 框架设置页调用 — 已配置 key 的长度 (仅回显长度, 不暴露明文)。 */
        fun configuredApiKeyLength(): Int {
            val env = System.getenv("TAVILY_API_KEY")?.takeIf { it.isNotBlank() }
            return if (env != null) env.length else storedConfiguredKey().length
        }

        /** 从配置文件读取真实 key (兼容旧明文 + "obf:" 混淆), 空/缺失返回 ""。 */
        internal fun storedConfiguredKey(): String = runCatching {
            val obj = Json.parseToJsonElement(configFile().readText()).jsonObject
            val stored = obj["apiKey"]?.jsonPrimitive?.content.orEmpty()
            if (stored.startsWith("obf:")) deobfuscate(stored.removePrefix("obf:")) else stored
        }.getOrDefault("")
    }

    private val configFile: File get() = configFile()

    /**
     * API key: env 优先, 其次配置文件 (tavily.setup 写入)。
     * 配置值带 "obf:" 前缀 → 反混淆读取; 无前缀 → 旧版本明文配置, 向后兼容。
     * 确认无明文日志输出: 本文件所有日志/返回值仅回显 key 长度 (P2-9)。
     */
    private val apiKey: String get() =
        System.getenv("TAVILY_API_KEY")?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val obj = Json.parseToJsonElement(configFile.readText()).jsonObject
                val stored = obj["apiKey"]?.jsonPrimitive?.content.orEmpty()
                if (stored.startsWith("obf:")) deobfuscate(stored.removePrefix("obf:")) else stored
            }.getOrDefault("")

    /** 轻量混淆 (P2 折中, 见类头注释) — UTF-8 字节 XOR, 防"目录浏览即得明文"。
     *  internal 为测试可见性 (编解码单测); 委托 companion 避免重复实现。 */
    internal fun obfuscate(plain: String): String = Companion.obfuscate(plain)

    /** internal 为测试可见性 (编解码单测); 委托 companion 避免重复实现。 */
    internal fun deobfuscate(encoded: String): String = Companion.deobfuscate(encoded)

    private val keyError: String
        get() = "Tavily API key 未配置。用 `tavily.setup --from-file <路径>` 写入配置 (key 不进会话历史) 或设置环境变量 TAVILY_API_KEY。"

    // ── tavily.setup ────────────────────────────────────────────────────
    // P2-9 (2026-08-06): key 来源三选一 —
    //   --from-file <路径>     推荐: key 只出现在文件系统, 命令文本/会话历史/审计日志不落 key
    //   --from-clipboard       插件层无系统剪贴板能力 (纯 JVM kernel), 明确报错引导 --from-file
    //   内联 <key>             兼容旧用法 — 但命令原文会被 kernel Pipeline 审计日志原样记录,
    //                          Sanitizer 不覆盖 tvly- 前缀 (kernel 冻结), 故输出零明文+长度回显
    // internal 为测试可见性 (key 来源/脱敏单测)。
    internal suspend fun setup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.contains("--from-clipboard")) {
            return ExecutionResult.fail(
                "插件层无系统剪贴板访问能力 (插件仅依赖纯 JVM kernel, 无 Android 剪贴板 API)。" +
                "请改用 `tavily.setup --from-file <路径>` — key 从文件读取, 不进入会话历史/审计日志。",
                errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val fromFileIdx = args.indexOf("--from-file")
        if (fromFileIdx >= 0) {
            val path = args.getOrNull(fromFileIdx + 1)
            if (path.isNullOrBlank()) {
                return ExecutionResult.fail(
                    "用法: `tavily.setup --from-file <路径>` — key 从文件首行读取, 不进入会话历史/审计日志。",
                    errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
            val file = File(path)
            if (!file.isFile) {
                return ExecutionResult.fail(
                    "key 文件不存在或不是文件: $path (用 `--from-file` 时 key 不进入会话历史/审计日志)",
                    errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
            val key = try {
                file.bufferedReader(Charsets.UTF_8).use { it.readLine() }?.trim().orEmpty()
            } catch (e: Exception) {
                return ExecutionResult.fail("读取 key 文件失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            if (key.isBlank()) {
                return ExecutionResult.fail("key 文件首行为空: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
            return storeKey(key)
        }

        val key = args.firstOrNull()?.trim()
        if (key.isNullOrBlank()) {
            val configured = apiKey.isNotBlank()
            return ExecutionResult.ok(
                if (configured) "Tavily API key 已配置 (key 长度 ${apiKey.length})\n用 `tavily.setup --from-file <路径>` 更新, 传空串清除。"
                else "Tavily API key 未配置。用法: `tavily.setup --from-file <路径>` 或 `tavily.setup <key>`"
            )
        }
        return storeKey(key)
    }

    /** 落盘 (混淆存储) + 只回显 key 长度的成功消息 — key 原文不进任何输出 (P2-9)。 */
    private fun storeKey(key: String): ExecutionResult = try {
        configFile.parentFile?.mkdirs()
        // P2 修复: 落盘混淆存储 (XOR + "obf:" 前缀), 替代明文写入
        configFile.writeText(buildJsonObject { put("apiKey", "obf:" + obfuscate(key)) }.toString())
        ExecutionResult.ok(
            "Tavily API key 已保存 (key 长度 ${key.length})。提示: 内联传参会把 key 留在会话历史, 建议用 `--from-file`。"
        )
    } catch (e: Exception) {
        ExecutionResult.fail("保存失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
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
