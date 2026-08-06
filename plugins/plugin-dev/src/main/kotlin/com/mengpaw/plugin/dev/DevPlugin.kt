// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.dev

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.*
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.security.MessageDigest

/**
 * Plugin development CLI — scaffold, build, audit, and share custom plugins.
 * Agents use these commands to create and publish their own plugins.
 *
 * ## 职责拆分 (批次3)
 * 无状态逻辑拆到同包顶层函数 (公开 API 零变化):
 * - [auditPlugin] (DevPluginAudit) — plugin.audit 审计规则
 * - [examplesCommand] (DevPluginExamples) — plugin.examples 模板参考
 * - [parsePluginArgs]/[pluginNameToId]/[JAR_BUILD_TEMPLATE]/[JAR_PLUGIN_TEMPLATE]
 *   (DevPluginSupport) — 参数解析 / 骨架模板
 */
class DevPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "dev-plugin",
        name = "插件开发工具",
        version = "", // 内置插件, 随 Shell APK 版本更新
        description = "创建、审计、构建、分享自建插件——Agent 可自主扩展 MengPaw 功能",
        author = "MengPaw Core",
        type = PluginType.NATIVE,
        // 全名 = 命名空间 "dev" + commands 键 (dev.plugin.create 等) —
        // 此前为空数组违反自身 audit 规则 (metadata.commands 必须列出所有命令),
        // 影响 CLI.md 生成与命令触达
        commands = listOf(
            "dev.plugin.create", "dev.plugin.audit", "dev.plugin.share",
            "dev.plugin.examples", "dev.plugin.keywords", "dev.plugin.guide"
        )
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "plugin.create"  to ::create,
        "plugin.audit"   to ::auditPlugin,
        "plugin.share"   to ::share,
        "plugin.examples" to ::examplesCommand,
        "plugin.keywords" to ::keywords,
        "plugin.guide"   to ::guide,
    )

    /** 安装即写入能力边界文档 (用户可读), 保持随插件分发. */
    override suspend fun onInstall(context: com.mengpaw.kernel.plugin.PluginContext) {
        PluginDevGuide.ensureWritten()
    }

    // ── plugin.create ────────────────────────────────────────────────

    private suspend fun create(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val a = parsePluginArgs(args)
        val type = a["type"] ?: return ExecutionResult.fail("缺少 --type (script|native)")
        val name = a["name"] ?: return ExecutionResult.fail("缺少 --name 插件名称")
        val id = pluginNameToId(name)
        val dir = File(DataPaths.PLUGIN_CACHE, id)

        if (dir.exists()) return ExecutionResult.fail("插件 '$id' 已存在")

        when (type.lowercase()) {
            "script" -> {
                val ns = id.removeSuffix("-plugin")
                val json = buildString {
                    appendLine("{")
                    appendLine("  \"id\": \"$id\",")
                    appendLine("  \"name\": \"$name\",")
                    appendLine("  \"version\": \"0.1.0\",")
                    appendLine("  \"type\": \"SCRIPT\",")
                    appendLine("  \"author\": \"${a["author"] ?: "Agent-Unknown"}\",")
                    appendLine("  \"description\": \"${a["desc"] ?: "示例插件 — 请编辑此描述"}\",")
                    appendLine("  \"commands\": {")
                    appendLine("    \"hello\": {")
                    appendLine("      \"shell\": \"echo 'Hello from $name!'\",")
                    appendLine("      \"params\": [],")
                    appendLine("      \"description\": \"示例命令\"")
                    appendLine("    }")
                    appendLine("  },")
                    appendLine("  \"keywords\": {")
                    appendLine("    \"hello\": {")
                    appendLine("      \"zh\": [\"替换为中文同义词\"],")
                    appendLine("      \"en\": [\"replace-with-english-synonyms\"]")
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

    // ── plugin.keywords ───────────────────────────────────────────────

    /** 查看或编辑插件命令的关键词配置. */
    private suspend fun keywords(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val a = parsePluginArgs(args)
        val id = a["target"] ?: return ExecutionResult.fail("用法: plugin.keywords --target <插件ID>")

        // 尝试从已安装+激活的插件读取 metadata
        val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
        val plugin = try { pm.get(id) } catch (_: Exception) { null }
        val kws = plugin?.metadata?.commandKeywords

        if (plugin == null) return ExecutionResult.ok("插件 '$id' 未安装或未激活. 已安装但未激活的插件无关键词信息.")
        if (kws.isNullOrEmpty()) return ExecutionResult.ok(buildString {
            appendLine("插件 '$id' 未定义关键词. Agent 搜索时使用自动生成的基础关键词.")
            appendLine()
            appendLine("建议添加: 在 plugin.json 中增加 keywords 字段, 或在 Kotlin metadata 中设置 commandKeywords.")
        })

        return ExecutionResult.ok(buildString {
            appendLine("=== $id · 命令关键词 ===")
            appendLine()
            kws.forEach { (cmd, kw) ->
                appendLine("$cmd:")
                if (kw.zh.isNotEmpty()) appendLine("  中文: ${kw.zh.joinToString(", ")}")
                if (kw.en.isNotEmpty()) appendLine("  英文: ${kw.en.joinToString(", ")}")
                appendLine()
            }
            appendLine("提示: 修改 plugin.json 后重新激活以更新关键词.")
        })
    }

    // ── plugin.guide ──────────────────────────────────────────────────

    /** 查看插件开发工具能力边界文档. 同时落盘到 插件文档/ 供用户阅读. */
    private suspend fun guide(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = PluginDevGuide.ensureWritten()
        return ExecutionResult.ok(buildString {
            appendLine("=== 插件开发工具 · 能力边界 ===")
            appendLine()
            append(PluginDevGuide.CONTENT)
            appendLine()
            if (path.isNotBlank()) {
                appendLine("---")
                appendLine("📄 文档已写入: $path（用户可在文件管理器阅读）")
                appendLine("更新时机: 安装/升级 dev-plugin 时或本命令执行时自动刷新")
            }
        })
    }

    // ── plugin.share ─────────────────────────────────────────────────

    private suspend fun share(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val a = parsePluginArgs(args)
        val pluginId = a["plugin"] ?: return ExecutionResult.fail("缺少 --plugin <插件ID>")
        val framework = a["to"] ?: return ExecutionResult.fail("缺少 --to <框架名称>")

        val dir = File(DataPaths.PLUGIN_CACHE, pluginId)
        if (!dir.exists()) return ExecutionResult.fail("插件 '$pluginId' 不存在")

        // Audit before sharing
        val auditResult = auditPlugin(listOf("--target", pluginId), ctx)
        if (auditResult.output.contains("🔴")) {
            return ExecutionResult.fail("插件审计未通过，请先修复问题再分享:\n${auditResult.output}")
        }

        // Generate SHA256
        val hash = dir.walkTopDown().filter { it.isFile }.sortedBy { it.name }
            .fold(MessageDigest.getInstance("SHA-256")) { md, f ->
                md.update(f.readBytes()); md
            }.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) } // Locale.ROOT: 阿拉伯语设备 %02x 畸形 (P2)

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
}
