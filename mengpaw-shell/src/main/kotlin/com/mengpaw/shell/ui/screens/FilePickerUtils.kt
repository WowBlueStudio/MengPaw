// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import android.content.Context
import android.net.Uri
import java.io.File

/** 将 content:// URI 拷贝到 Agent 工作区，返回文件路径通过回调插入输入框。 */
fun handleFilePicked(
    uri: Uri,
    context: Context,
    viewModel: AgentViewModel,
    uploadDir: String,
    onPath: (String) -> Unit
) {
    try {
        val dir = File(
            if (uploadDir.isNotBlank()) uploadDir
            else com.mengpaw.kernel.DataPaths.AGENTS + "/MengPaw/workspace"
        )
        dir.mkdirs()
        val ext = context.contentResolver.getType(uri)?.let { mime ->
            when {
                mime.contains("png") -> ".png"
                mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
                mime.contains("pdf") -> ".pdf"
                mime.contains("text/plain") -> ".txt"
                mime.contains("text/html") -> ".html"
                else -> ""
            }
        } ?: ""
        val name = "upload_${System.currentTimeMillis()}$ext"
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 4096) }
        }
        if (target.exists() && target.length() > 0) {
            val MAX_SIZE = 50L * 1024 * 1024
            if (target.length() > MAX_SIZE) {
                target.delete()
                onPath("⚠️ 文件超过 50MB 上限，已丢弃\n")
            } else {
                onPath("📎 ${target.absolutePath}\n")
            }
        }
    } catch (_: Exception) { }
}
