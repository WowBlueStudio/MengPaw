// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.content.Context
import android.net.Uri
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.formatFromMime
import com.mengpaw.shell.ui.screens.model.typeFromMime
import java.io.File

/**
 * 将 content:// URI 拷贝到 Agent 工作区，产出结构化附件 [AttachmentData]（v0.33.0+）。
 *
 * 改造前: 把 `绝对路径` 文本插进输入框；
 * 改造后: 附件对象进 pendingAttachments, 气泡渲染卡片 + 发送时按类型挂二进制通道。
 */
fun handleFilePicked(
    uri: Uri,
    context: Context,
    uploadDir: String,
    onAttachment: (AttachmentData) -> Unit,
    onError: (String) -> Unit = {}
) {
    try {
        val dir = File(
            if (uploadDir.isNotBlank()) uploadDir
            else com.mengpaw.kernel.DataPaths.AGENTS + "/MengPaw/workspace"
        )
        dir.mkdirs()
        val mime = context.contentResolver.getType(uri)
        // 原始文件名 — 必须查 DISPLAY_NAME: DocumentsUI 的 content:// URI lastPathSegment
        // 是文档 ID 编号 (如 msf%3A12345) 不是文件名; 查不到再回退 lastPathSegment (FileProvider 路径)
        val originalName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        val ext = when {
            originalName.contains('.') -> originalName.substringAfterLast('.')
            else -> mime?.let { m ->
                when {
                    m.contains("png") -> "png"
                    m.contains("jpeg") || m.contains("jpg") -> "jpg"
                    m.contains("pdf") -> "pdf"
                    m.contains("audio/mp4") || m.contains("audio/aac") || m.contains("audio/mpeg") -> "m4a"
                    m.contains("audio/wav") -> "wav"
                    m.contains("text/plain") -> "txt"
                    m.contains("text/html") -> "html"
                    else -> ""
                }
            } ?: ""
        }
        val name = "upload_${System.currentTimeMillis()}_${originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 4096) }
        }
        if (target.exists() && target.length() > 0) {
            val MAX_SIZE = 50L * 1024 * 1024
            if (target.length() > MAX_SIZE) {
                target.delete()
                onError("文件超过 50MB 上限，已丢弃")
            } else {
                onAttachment(
                    AttachmentData(
                        type = typeFromMime(mime, originalName),
                        path = target.absolutePath,
                        mimeType = mime ?: "",
                        name = originalName,
                        size = target.length(),
                        format = formatFromMime(mime, originalName)
                    )
                )
            }
        } else {
            onError("文件读取失败")
        }
    } catch (_: Exception) {
        onError("文件处理失败")
    }
}

/** 查 content:// URI 的真实显示文件名 (OpenableColumns.DISPLAY_NAME)。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
        } else null
    }
} catch (_: Exception) { null }
