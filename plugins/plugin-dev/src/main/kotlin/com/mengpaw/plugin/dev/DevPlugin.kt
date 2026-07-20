// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.dev

import com.mengpaw.core.DataPaths
import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.plugin.*
import com.mengpaw.core.error.ErrorCollector
import java.io.File
import java.security.MessageDigest

/**
 * Plugin development CLI — scaffold, build, audit, and share custom plugins.
 * Agents use these commands to create and publish their own plugins.
 */
class DevPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "dev-plugin",
        name = "插件开发工具",
        version = "0.1.0",
        description = "创建、审计、构建、分享自建插件——Agent 可自主扩展 MengPaw 功能",
        author = "MengPaw Core",
        type = PluginType.NATIVE
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "plugin.create"  to ::create,
        "plugin.audit"   to ::audit,
        "plugin.share"   to ::share,
        "plugin.examples" to ::examples,
    )

    // ── plugin.create ────────────────────────────────────────────────

    private fun parseArgs(raw: List<String>): Map<String, String> {
        val m = mutableMapOf<String, String>()
        var i = 0
        while (i < raw.size) {
            when {
                raw[i].startsWith("--") && i + 1 < raw.size -> {
                    m[raw[i].removePrefix("--")] = raw[i + 1]; i += 2
                }
                raw[i].startsWith("--") -> { m[raw[i].removePrefix("--")] = "true"; i++ }
                else -> { m["arg$i"] = raw[i]; i++ }
            }
        }
        return m
    }

    private suspend fun create(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val a = parseArgs(args)
        val type = a["type"] ?: return ExecutionResult.fail("缺少 --type (script|native)")
        val name = a["name"] ?: return ExecutionResult.fail("缺少 --name 插件名称")
        val id = nameToId(name)
        val dir = File(DataPaths.PLUGIN_CACHE, id)

        if (dir.exists()) return ExecutionResult.fail("插件 '$id' 已存在")

        when (type.lowercase()) {
            "script" -> {
                val json = buildString {
                    appendLine("{")
                    appendLine("  \"id\": \"$id\",")
                    appendLine("  \"name\": \"$name\",")
                    appendLine("  \"version\": \"0.1.0\",")
                    appendLine("  \"type\": \"SCRIPT\",")
                    appendLine("  \"author\": \"${a["author"] ?: "Agent-Unknown"}\",")
                    appendLine("  \"description\": \"${a["desc"] ?: ""}\",")
                    appendLine("  \"commands\": {")
                    appendLine("    \"$id.hello\": {")
                    appendLine("      \"shell\": \"echo 'Hello from $name!'\",")
                    appendLine("      \"params\": [],")
                    appendLine("      \"description\": \"示例命令\"")
                    appendLine("    }")
                    appendLine("  }")
                    appendLine("}")
                }
                dir.mkdirs()
                return try {
                    File(dir, "plugin.json").writeText(json)
                    ExecutionResult.ok("SCRIPT 插件骨架已创建: ${dir.absolutePath}\n编辑 plugin.json 添加命令后即可使用。")
                } catch (e: Exception) {
                    ErrorCollector.report(e, "DevPlugin.create")
                    ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
                }
            }
            "native" -> {
                val ns = id.removeSuffix("-plugin")
                val pkg = "com.mengpaw.plugin.${id.replace("-", "")}"
                val cls = name.filter { it.isLetterOrDigit() }
                dir.mkdirs()
                return try {
                    File(dir, "build.gradle.kts").writeText(JAR_BUILD_TEMPLATE.replace("{ID}", id))
                    val srcDir = File(dir, "src/main/kotlin/${pkg.replace('.', '/')}")
                    srcDir.mkdirs()
                    File(srcDir, "${cls}Plugin.kt")
                        .writeText(JAR_PLUGIN_TEMPLATE
                            .replace("{PKG}", pkg).replace("{CLS}", cls)
                            .replace("{ID}", id).replace("{NAME}", name).replace("{NS}", ns))
                    ExecutionResult.ok("NATIVE 插件骨架已创建: ${dir.absolutePath}\n\n下一步:\n1. 用 Android Studio 打开 ${id}/\n2. 修改 src/.../${cls}Plugin.kt 中的 example 命令\n3. 用 plugin.audit --target $id 检查\n4. 发布: plugin.share --plugin $id --to <框架>")
                } catch (e: Exception) {
                    ErrorCollector.report(e, "DevPlugin.create")
                    ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
                }
            }
            else -> return ExecutionResult.fail("type 必须是 'script' 或 'native'")
        }
    }

    // ── plugin.audit ──────────────────────────────────────────────────

    private suspend fun audit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val a = parseArgs(args)
        val target = a["target"] ?: return ExecutionResult.fail("缺少 --target <插件ID>")
        val dir = File(DataPaths.PLUGIN_CACHE, target)
        if (!dir.exists()) return ExecutionResult.fail("插件 '$target' 不存在")

        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Check plugin.json or source
        val jsonFile = File(dir, "plugin.json")
        val ktFiles = dir.walkTopDown().filter { it.extension == "kt" }.toList()

        if (jsonFile.exists()) {
            val json = try { jsonFile.readText() } catch (e: Exception) { ErrorCollector.report(e, "DevPlugin.audit"); "" }
            issues.addAll(auditScript(json))
        } else if (ktFiles.isNotEmpty()) {
            ktFiles.forEach { issues.addAll(auditKotlin(try { it.readText() } catch (e: Exception) { ErrorCollector.report(e, "DevPlugin.audit"); "" })) }
        } else {
            return ExecutionResult.fail("未找到 plugin.json 或 Kotlin 源文件")
        }

        // Common checks
        if (!jsonFile.exists() && ktFiles.isEmpty())
            issues.add("无 plugin.json 且无 Kotlin 源文件 — 无法判断插件类型")

        return ExecutionResult.ok(buildString {
            appendLine("=== 插件审计: $target ===")
            appendLine("MengPaw 安全规则 v0.1.0")
            appendLine("---")
            if (issues.isEmpty() && warnings.isEmpty()) {
                appendLine("✅ 审计通过 — 所有安全规则检查合格")
            } else {
                if (issues.isNotEmpty()) {
                    appendLine("🔴 阻断 (${issues.size} 项) — 必须修复后才能发布:")
                    issues.forEach { appendLine("   $it") }
                }
                if (warnings.isNotEmpty()) {
                    appendLine("🟡 建议 (${warnings.size} 项):")
                    warnings.forEach { appendLine("   $it") }
                }
            }
            appendLine("\n安全规则详见: PLUGIN_DEV_GUIDE.md §9")
        })
    }

    private fun auditScript(json: String): List<String> {
        val issues = mutableListOf<String>()

        // Metadata
        if (!json.contains("\"id\"")) issues.add("[元数据] 缺少 'id' 字段")
        if (!json.contains("\"version\"")) issues.add("[元数据] 缺少 'version' — 必须遵循 SemVer (如 0.1.0)")
        if (!json.contains("\"author\"") || json.contains("\"author\": \"\""))
            issues.add("[元数据] 缺少 'author' — 必须填写作者，不允许匿名")
        if (!json.contains("\"commands\"")) issues.add("[结构] 缺少 'commands' — 插件无命令")
        if (!json.contains("\"description\"") || json.contains("\"description\": \"\""))
            issues.add("[元数据] 缺少 'description' — 必须描述插件功能")

        // Type check
        if (json.contains("\"type\"") && !json.contains("\"SCRIPT\""))
            issues.add("[类型] SCRIPT 插件的 type 必须为 'SCRIPT'")
        if (!json.contains("\"shell\""))
            issues.add("[结构] SCRIPT 插件必须包含 shell 命令")

        // Dangerous shell commands
        for (d in listOf("rm -rf /", "rm -rf ~", "mkfs.", "dd if=", "> /dev/sda",
            ":(){ :|:& };:", "> /dev/null;", "chmod 777", "sudo ", "su -"))
            if (json.contains(d)) issues.add("[Shell] 包含危险命令: '$d'")

        // Shell injection
        if (json.contains("; ") && json.contains("shell")) issues.add("[Shell] 可能包含命令注入 (; 分隔符)")
        if (Regex("\\$\\(").containsMatchIn(json)) issues.add("[Shell] 可能包含命令替换 \$(...)")
        if (json.contains("|") && !json.contains("wttr.in") && !json.contains("grep"))
            issues.add("[Shell] 包含管道符 — 确认非命令注入")

        // URL safety
        if (json.contains("http://") && !json.contains("https://"))
            issues.add("[网络] 使用了 HTTP 明文 — 必须使用 HTTPS")
        if (json.contains("file://")) issues.add("[网络] 包含 file:// 协议 — 禁止")
        if (json.contains("localhost") || json.contains("127.0.0.1"))
            issues.add("[网络] 包含 localhost — 可能尝试内网攻击")

        // Size limits
        if (json.length > 50000) issues.add("[大小] 插件定义过大 (${json.length/1024}KB) — 建议 ≤ 50KB")

        return issues
    }

    private fun auditKotlin(code: String): List<String> {
        val issues = mutableListOf<String>()

        // Structure
        if (!code.contains("class ") || !code.contains("Plugin"))
            issues.add("[结构] 未找到 Plugin 类实现")
        if (!code.contains("override val metadata"))
            issues.add("[元数据] 缺少 metadata — 必须声明 id/name/version/author/permissions")
        if (!code.contains("override val commands"))
            issues.add("[结构] 缺少 commands — 插件无命令")
        if (!code.contains("PluginType.NATIVE"))
            issues.add("[类型] metadata.type 必须声明为 PluginType.NATIVE")
        if (!code.contains("permissions"))
            issues.add("[元数据] 缺少 permissions 声明 — 必须列出所有需要的 Android 权限")
        if (!code.contains("minCoreVersion"))
            issues.add("[元数据] 缺少 minCoreVersion — 必须声明最低框架版本 (≥ 0.2.0)")

        // Null safety
        if (code.contains("!!"))
            issues.add("[空安全] 使用了 '!!' 强制解包 — NPE 崩溃风险，改用 '?:' 或 'as?'")

        // Concurrency
        if (code.contains("Thread.sleep") || code.contains("while (true)")
            || code.contains("runBlocking"))
            issues.add("[并发] 阻塞调用 — Android 上应使用协程 suspend")

        // File IO safety
        if (!code.contains("try {") && (code.contains("File(") || code.contains(".readText")
            || code.contains(".writeText") || code.contains(".listFiles")))
            issues.add("[文件IO] 未包裹 try/catch — 文件损坏或权限不足会崩溃")

        // Network safety
        if (code.contains("http://") && !code.contains("https://"))
            issues.add("[网络] HTTP 明文 — 必须使用 HTTPS")
        if (code.contains("bodyAsText()") && !code.contains(".take("))
            issues.add("[网络] 响应未截断 — 建议 .take(10000) 防止内存溢出")
        if (code.contains("connectTimeout") && !code.contains("readTimeout"))
            issues.add("[网络] 缺少 readTimeout — 可能无限等待")

        // Privacy
        if (code.contains("\"sk-\"") || code.contains("apiKey") || code.contains("api_key"))
            issues.add("[隐私] 可能硬编码 API Key — 使用 Sanitizer 过滤")
        if (code.contains("ContactsContract") || code.contains("Telephony") ||
            code.contains("CallLog"))
            issues.add("[隐私] 可能访问通讯录/通话记录 — 必须用户明确授权")

        // Path traversal
        if (code.contains("..") && code.contains("\"path\""))
            issues.add("[路径] 可能存在路径穿越 (..) — 参数校验不充分")

        return issues
    }

    // ── plugin.examples ───────────────────────────────────────────────

    private suspend fun examples(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
=== MengPaw 插件开发参考 ===

【文件操作插件模板】(参考 fs-plugin)

import com.mengpaw.core.cli.*
import com.mengpaw.core.plugin.*
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

    // ── plugin.share ─────────────────────────────────────────────────

    private suspend fun share(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val a = parseArgs(args)
        val pluginId = a["plugin"] ?: return ExecutionResult.fail("缺少 --plugin <插件ID>")
        val framework = a["to"] ?: return ExecutionResult.fail("缺少 --to <框架名称>")

        val dir = File(DataPaths.PLUGIN_CACHE, pluginId)
        if (!dir.exists()) return ExecutionResult.fail("插件 '$pluginId' 不存在")

        // Audit before sharing
        val auditResult = audit(listOf("--target", pluginId), ctx)
        if (auditResult.output.contains("🔴")) {
            return ExecutionResult.fail("插件审计未通过，请先修复问题再分享:\n${auditResult.output}")
        }

        // Generate SHA256
        val hash = dir.walkTopDown().filter { it.isFile }.sortedBy { it.name }
            .fold(MessageDigest.getInstance("SHA-256")) { md, f ->
                md.update(f.readBytes()); md
            }.digest().joinToString("") { "%02x".format(it) }

        // Create share bundle
        val shareDir = File(DataPaths.AGENTS, "acp/shares/$pluginId")
        shareDir.mkdirs()
        dir.copyRecursively(shareDir, overwrite = true)
        try { File(shareDir, "SHARE_MANIFEST.txt").writeText(
            "plugin=$pluginId\nframework=$framework\nsha256=$hash\ntimestamp=${System.currentTimeMillis()}\n"
        ) } catch (e: Exception) { ErrorCollector.report(e, "DevPlugin.share") }

        return ExecutionResult.ok(
            "插件 '$pluginId' 已准备分享给 '$framework'。\n" +
            "SHA256: $hash\n" +
            "对方 Agent 将收到安装请求，需用户同意。"
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun nameToId(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "-").trim('-') + "-plugin"

    companion object {
        val JAR_BUILD_TEMPLATE = """
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.mengpaw.plugin.{ID}"
    compileSdk = 35
    defaultConfig { minSdk = 26; versionCode = 1; versionName = "0.1.0" }
}
dependencies { implementation(project(":mengpaw-core")) }
""".trimIndent()

        val JAR_PLUGIN_TEMPLATE = """
package {PKG}

import com.mengpaw.core.cli.*
import com.mengpaw.core.plugin.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class {CLS}Plugin : Plugin {
    override val metadata = PluginMetadata(
        id = "{ID}", name = "{NAME}", version = "0.1.0",
        type = PluginType.NATIVE, author = "", description = "",
        permissions = emptyList(),
        minCoreVersion = "0.2.0",
        commands = listOf("{NS}.example")
    )
    override val commands: Map<String, CommandHandler> = mapOf(
        "example" to ::example
    )

    private suspend fun example(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // TODO: 替换为你的命令逻辑
        return ExecutionResult.ok("{NAME} is ready!")
    }

    private fun resolvePath(path: String, ctx: ExecutionContext): String {
        val f = File(path)
        return if (f.isAbsolute) f.absolutePath else File(ctx.workDir, path).absolutePath
    }
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${'$'}bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
    private fun formatDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(ms))
}
""".trimIndent()
    }
}
