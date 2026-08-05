// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens.model

import com.mengpaw.kernel.llm.AttachmentPayload
import com.mengpaw.kernel.session.AttachmentData

/**
 * 附件类型/格式判定 + 发送正文合成工具 (v0.33.0+)。
 *
 * 上行设计: 附件不进输入框文本, 以 [AttachmentData] 独立携带;
 * 发送时 [buildTaskContent] 合成给 LLM 的纯文本 content:
 * - document/file → `📎 path`（与历史行为一致, LLM 用 fs 工具读）
 * - image → `[图片附件] 📎 path`（二进制走 _image 通道）
 * - audio → `[语音消息] 📎 path`（二进制走 _audio_data 通道, 路径供 LLM 引用/存档）
 */

/** 按 MIME/文件名后缀判定附件类型: image/audio/video/document/file。 */
fun typeFromMime(mime: String?, name: String = ""): String {
    val m = mime?.lowercase() ?: ""
    val n = name.lowercase()
    val ext = n.substringAfterLast('.', "")
    return when {
        m.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic") -> "image"
        m.startsWith("audio/") || ext in setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus") -> "audio"
        m.startsWith("video/") || ext in setOf("mp4", "mov", "webm", "mkv", "avi", "3gp") -> "video"
        m.contains("pdf") || ext == "pdf" -> "document"
        m.contains("text/") || ext in setOf("txt", "md", "markdown") -> "document"
        m.contains("word") || ext in setOf("doc", "docx") -> "document"
        m.contains("excel") || ext in setOf("xls", "xlsx", "csv") -> "document"
        m.contains("presentation") || ext in setOf("ppt", "pptx") -> "document"
        else -> "file"
    }
}

/** input_audio 格式映射 (OpenAI 兼容: m4a/mp3/wav)。 */
fun formatFromMime(mime: String?, name: String = ""): String {
    val m = mime?.lowercase() ?: ""
    val ext = name.lowercase().substringAfterLast('.', "")
    return when {
        m.contains("mp3") || ext == "mp3" -> "mp3"
        m.contains("wav") || ext == "wav" -> "wav"
        else -> "m4a"
    }
}

/** 发送正文合成 — 附件以文本标注形式并入 content（LLM 可读可引用）。 */
fun buildTaskContent(text: String, attachments: List<AttachmentData>): String {
    val attLines = attachments.joinToString("") { att ->
        val mark = when (att.type) {
            "image" -> "[图片附件] "
            "audio" -> "[语音消息] "
            else -> ""
        }
        "\n$mark📎 ${att.path}"
    }
    return if (attLines.isBlank()) text else "$text$attLines"
}

/** 二进制通道上限（与 AttachmentPayload 常量对齐, 供 UI 提示引用）。 */
const val IMAGE_BINARY_MAX = AttachmentPayload.IMAGE_BINARY_MAX
const val AUDIO_BINARY_MAX = AttachmentPayload.AUDIO_BINARY_MAX
