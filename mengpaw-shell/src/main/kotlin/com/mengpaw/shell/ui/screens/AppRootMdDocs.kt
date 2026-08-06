// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mengpaw.shell.ui.localization.AppStrings

// ── 工作区 md 文档助手 — 拆自 AppRoot.kt (2026-08-06, >400 行文件拆分批次4) ──

/**
 * Extract a human-readable summary from a markdown file.
 * Skips YAML frontmatter (lines between --- delimiters) and returns
 * the first heading or meaningful line.
 */
internal fun extractSummary(markdown: String): String {
    val lines = markdown.lines()
    var inFrontmatter = false
    var frontmatterCount = 0
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed == "---") {
            frontmatterCount++
            if (frontmatterCount == 1) { inFrontmatter = true; continue }
            if (frontmatterCount >= 2) { inFrontmatter = false; continue }
        }
        if (inFrontmatter) continue
        if (trimmed.startsWith("#")) return trimmed.removePrefix("#").trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("_") && !trimmed.startsWith(">"))
            return trimmed.take(60)
    }
    return ""
}

/**
 * 用系统其他软件打开工作区 md 文档 — FileProvider 共享 + ACTION_VIEW。
 * 优先 text/markdown MIME; 无处理器时回退 text/plain; 两者皆无 → Toast 提示。
 * 选择器中出现 MP 浏览器时由浏览器自行渲染 (content:// md 支持见浏览器侧)。
 */
internal fun openDocExternally(context: Context, file: java.io.File, strings: AppStrings) {
    fun launch(mime: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }
    try {
        if (launch("text/markdown") || launch("text/plain")) return
        android.widget.Toast.makeText(context, strings.editOpenFailed, android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "${strings.editOpenFailed} ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
