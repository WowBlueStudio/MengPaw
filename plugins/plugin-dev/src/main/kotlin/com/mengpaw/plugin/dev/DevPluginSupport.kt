// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.dev

/**
 * DevPlugin 共用工具与骨架模板 — 从 DevPlugin 拆出 (无状态)。
 *
 * 参数解析 / 插件名→ID / 脚本与原生插件骨架模板。
 */
internal fun parsePluginArgs(raw: List<String>): Map<String, String> {
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

internal fun pluginNameToId(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]"), "-").trim('-') + "-plugin"

internal val JAR_BUILD_TEMPLATE = """
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

internal val JAR_PLUGIN_TEMPLATE = """
package {PKG}

import com.mengpaw.kernel.cli.*
import com.mengpaw.kernel.plugin.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class {CLS}Plugin : Plugin {
    override val metadata = PluginMetadata(
        id = "{ID}", name = "{NAME}", version = "0.1.0",
        type = PluginType.NATIVE, author = "", description = "",
        permissions = emptyList(),
        minCoreVersion = "0.2.0",
        ports = emptyList(), // 声明插件占用的端口 (如 listOf(8xxx)) — 与内核保留端口冲突会被拒绝安装
        commands = listOf("{NS}.example"),
        commandKeywords = mapOf(
            "example" to CommandKeywords(
                zh = listOf("示例", "样例", "demo", "测试"),
                en = listOf("example", "demo", "test", "sample")
            )
        )
    )
    override val commands: Map<String, CommandHandler> = mapOf(
        "example" to ::example
    )

    private suspend fun example(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // TODO: 替换为你的命令逻辑
        return ExecutionResult.ok("{NAME} is ready!")
    }

    private fun resolvePath(path: String, ctx: ExecutionContext): String {
        return try {
            val f = File(path)
            if (f.isAbsolute) f.absolutePath else File(ctx.workDir, path).absolutePath
        } catch (e: Exception) { path }
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
