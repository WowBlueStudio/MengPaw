// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.dev

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * DevPlugin 审计逻辑 — 从 DevPlugin 拆出 (无状态, 顶层函数)。
 *
 * plugin.audit 命令: 对 SCRIPT (plugin.json) 与 NATIVE (Kotlin 源码)
 * 执行 MengPaw 安全规则 v0.1.0 检查。
 */
internal suspend fun auditPlugin(args: List<String>, ctx: ExecutionContext): ExecutionResult {
    val a = parsePluginArgs(args)
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

    // Keyword check
    if (!json.contains("\"keywords\""))
        issues.add("[检索] 🟡 缺少 keywords — 建议添加中英文同义词以提升 self.search 可发现性")

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

    // Keyword check
    if (!code.contains("commandKeywords") && !code.contains("CommandKeywords"))
        issues.add("[检索] 🟡 缺少 commandKeywords — 建议添加中英文同义词以提升 self.search 可发现性")

    // Port check — 声明端口不能与内核保留端口 (ACP 9876) 冲突
    if (code.contains("ports = listOf(")) {
        val declared = Regex("ports\\s*=\\s*listOf\\s*\\(([^)]*)\\)")
            .find(code)?.groupValues?.get(1) ?: ""
        if (declared.contains("9876"))
            issues.add("[端口] 🔴 声明端口 9876 与内核 ACP 保留端口冲突 — 安装会被 PluginManager 拒绝")
        Regex("\\d{1,5}").findAll(declared).forEach { m ->
            val p = m.value.toInt()
            if (p !in 1..65535)
                issues.add("[端口] 🟡 端口声明 $p 超出有效范围 (1-65535)")
        }
    }

    return issues
}
