// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.dev

import com.mengpaw.core.DataPaths
import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.plugin.*
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
        "plugin.create" to ::create,
        "plugin.audit"  to ::audit,
        "plugin.share"  to ::share,
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
                File(dir, "plugin.json").writeText(json)
                return ExecutionResult.ok("SCRIPT 插件骨架已创建: ${dir.absolutePath}\n编辑 plugin.json 添加命令后即可使用。")
            }
            "native" -> {
                dir.mkdirs()
                File(dir, "build.gradle.kts").writeText(JAR_BUILD_TEMPLATE.replace("{ID}", id).replace("{NAME}", name))
                val srcDir = File(dir, "src/main/kotlin/com/mengpaw/plugin/${id.replace("-", "")}")
                srcDir.mkdirs()
                File(srcDir, "${name.filter { it.isLetterOrDigit() }}Plugin.kt")
                    .writeText(JAR_PLUGIN_TEMPLATE.replace("{ID}", id).replace("{NAME}", name))
                return ExecutionResult.ok("NATIVE 插件骨架已创建: ${dir.absolutePath}\n用 Android Studio 打开后编译。")
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
            val json = try { jsonFile.readText() } catch (_: Exception) { "" }
            issues.addAll(auditScript(json))
        } else if (ktFiles.isNotEmpty()) {
            ktFiles.forEach { issues.addAll(auditKotlin(it.readText())) }
        } else {
            return ExecutionResult.fail("未找到 plugin.json 或 Kotlin 源文件")
        }

        // Common checks
        if (!jsonFile.exists() && ktFiles.isEmpty())
            issues.add("无 plugin.json 且无 Kotlin 源文件 — 无法判断插件类型")

        return ExecutionResult.ok(buildString {
            appendLine("=== 插件审计: $target ===")
            if (issues.isEmpty() && warnings.isEmpty()) {
                appendLine("✅ 未发现问题")
            } else {
                issues.forEach { appendLine("🔴 $it") }
                warnings.forEach { appendLine("🟡 $it") }
            }
            appendLine("\n建议：修复所有 🔴 后再发布。🟡 是提醒，不阻塞。")
        })
    }

    private fun auditScript(json: String): List<String> {
        val issues = mutableListOf<String>()
        if (!json.contains("\"id\"")) issues.add("缺少 'id' 字段")
        if (!json.contains("\"version\"")) issues.add("缺少 'version' 字段 — 必须遵循 SemVer")
        if (!json.contains("\"commands\"")) issues.add("缺少 'commands' 字段 — 插件无命令")
        if (json.contains("\"type\"") && !json.contains("\"SCRIPT\""))
            issues.add("type 应为 'SCRIPT'")
        if (!json.contains("\"shell\"")) issues.add("SCRIPT 插件必须包含 shell 命令")
        // Dangerous commands check
        for (dangerous in listOf("rm -rf /", "mkfs.", "dd if=", "> /dev/", ":(){ :|:& };:")) {
            if (json.contains(dangerous)) issues.add("包含危险命令: '$dangerous' — 可能导致系统损坏")
        }
        return issues
    }

    private fun auditKotlin(code: String): List<String> {
        val issues = mutableListOf<String>()
        if (!code.contains("class ") || !code.contains("Plugin"))
            issues.add("未找到 Plugin 类实现")
        if (!code.contains("override val metadata"))
            issues.add("缺少 metadata 声明 — 必须声明插件元信息")
        if (!code.contains("override val commands"))
            issues.add("缺少 commands 声明 — 插件无命令")
        if (code.contains("!!"))
            issues.add("使用了 '!!' 强制解包 — 会导致 NPE 崩溃，改用 '?:' 或 'as?'")
        if (code.contains("Thread.sleep") || code.contains("while (true)"))
            issues.add("使用了阻塞调用 — Android 上应使用协程 suspend")
        if (!code.contains("try {") && code.contains("File("))
            issues.add("文件操作未包裹 try/catch — 文件损坏或权限不足会崩溃")
        if (code.contains("http://") && !code.contains("https://"))
            issues.add("使用了 HTTP 明文连接 — 建议升级到 HTTPS")
        return issues
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
        File(shareDir, "SHARE_MANIFEST.txt").writeText(
            "plugin=$pluginId\nframework=$framework\nsha256=$hash\ntimestamp=${System.currentTimeMillis()}\n"
        )

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
package com.mengpaw.plugin.{ID.replace("-", "")}

import com.mengpaw.core.plugin.*

class {NAME.filter { it.isLetterOrDigit() }}Plugin : Plugin {
    override val metadata = PluginMetadata(
        id = "{ID}", name = "{NAME}", version = "0.1.0",
        description = "", type = PluginType.NATIVE
    )
    override val commands: Map<String, CommandHandler> = mapOf(
        "{ID.replace("-plugin", "")}.hello" to { args, ctx ->
            ExecutionResult.ok("Hello from {NAME}!")
        }
    )
}
""".trimIndent()
    }
}
