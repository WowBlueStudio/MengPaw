// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.session.AttachmentData
import java.io.File
import java.util.Base64

/**
 * 附件二进制载荷组装（v0.33.0+）。
 *
 * 纯 Kotlin 零 Android 依赖 —— kernel 不依赖 BitmapFactory，图片不做缩图，
 * 靠文件大小上限控制请求体膨胀：
 * - image ≤ [IMAGE_BINARY_MAX] → `_image` = data URI（OpenAI image_url 格式）
 * - audio ≤ [AUDIO_BINARY_MAX] → `_audio_data`（base64）+ `_audio_format`
 * 超限/读失败静默跳过二进制 —— content 里已有 `[图片附件] 📎 path` 文本标注，
 * LLM 上下文不丢，仅多模态能力降级为路径引用。
 */
object AttachmentPayload {
    const val IMAGE_BINARY_MAX = 8L * 1024 * 1024
    const val AUDIO_BINARY_MAX = 15L * 1024 * 1024

    /**
     * 给 user 消息 map 追加二进制键。非 image/audio 附件或超限时原样返回。
     * 返回的 map 允许覆盖同键（有二进制时替换文本 content 键为 content 数组的铺垫由请求构建层完成）。
     */
    fun attachBinary(base: Map<String, String>, attachments: List<AttachmentData>): Map<String, String> {
        var result = base
        for (att in attachments) {
            val file = File(att.path)
            if (!file.exists() || !file.isFile || file.length() <= 0) continue
            when (att.type) {
                "image" -> {
                    if (file.length() <= IMAGE_BINARY_MAX) {
                        val b64 = readBase64(file) ?: continue
                        val mime = if (att.mimeType.isNotBlank()) att.mimeType else "image/jpeg"
                        result = result + ("_image" to "data:$mime;base64,$b64")
                    }
                }
                "audio" -> {
                    if (file.length() <= AUDIO_BINARY_MAX) {
                        val b64 = readBase64(file) ?: continue
                        val format = att.format.ifBlank { "m4a" }
                        result = result + ("_audio_data" to b64) + ("_audio_format" to format)
                    }
                }
                // video/document/file: v1 不走二进制通道（路径文本已在 content 内）
            }
        }
        return result
    }

    private fun readBase64(file: File): String? = try {
        Base64.getEncoder().encodeToString(file.readBytes())
    } catch (_: Exception) {
        null
    }
}
