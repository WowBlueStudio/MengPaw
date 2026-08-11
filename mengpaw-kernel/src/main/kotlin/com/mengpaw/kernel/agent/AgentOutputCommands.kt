// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * agent.output 输出目录命令 (v0.36.x 去重后仅存命令)。
 *
 * agent.read/write/ls/rm/mkdir 已随 Linux 命令通道合并移除 — Android 有等价命令
 * (cat/echo/ls/rm/mkdir), 见开发指南 §5.2.1。写后读回验证语义由系统提示词
 * 「结果纪律」+ Linux 通道写后验证提示承接。
 */
internal class AgentOutputCommands {

    /** agent.output — 显示输出目录。HTML/MD/PDF 等用户文档写出到此目录，文件管理器可访问。 */
    internal suspend fun output(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val dir = java.io.File(com.mengpaw.kernel.DataPaths.OUTPUT)
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles()?.sortedBy { if (it.isDirectory) 0 else 1 } ?: emptyList()
        return ExecutionResult.ok(buildString {
            appendLine("📂 输出目录: ${com.mengpaw.kernel.DataPaths.OUTPUT}")
            if (dir.canWrite()) {
                appendLine("   状态: 可写")
            } else {
                appendLine("   状态: ⚠️ 不可写")
                if (com.mengpaw.kernel.DataPaths.OUTPUT.startsWith("/storage/emulated/0/MengPaw")) {
                    appendLine("   └ 公共目录需『所有文件访问』权限: 设置 → 应用 → MengPaw → 权限 → 所有文件访问 → 允许")
                }
            }
            val totalSize = files.sumOf { it.length() }
            if (totalSize > 0) appendLine("   总大小: ${formatSize(totalSize)}")
            appendLine()
            if (files.isEmpty()) {
                appendLine("(空)")
            } else {
                files.forEach { f ->
                    val icon = if (f.isDirectory) "📁" else "📄"
                    val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                    appendLine("  $icon ${f.name}$size")
                }
                appendLine()
                appendLine("${files.size} 个项目")
            }
            appendLine()
            appendLine("写文件: echo '<内容>' > ${com.mengpaw.kernel.DataPaths.OUTPUT}/<文件名>")
            appendLine("示例: echo '<html内容>' > ${com.mengpaw.kernel.DataPaths.OUTPUT}/report.html")
            appendLine("写后必须 cat 读回验证内容一致，再交付链接。")
        })
    }

}

/** 人类可读文件大小 (agent.output / agent.storage 共用)。 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
}

/** 目录递归体积 (agent.storage 用)。 */
internal fun dirSize(dir: java.io.File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    dir.listFiles()?.forEach {
        total += if (it.isDirectory) dirSize(it) else it.length()
    }
    return total
}
