// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.serialization.Serializable

/**
 * 结构化附件（v0.33.0 起）。
 *
 * 消息正文仍为纯文本（[Message.content] 内含 `📎 路径` 标注，LLM 用 fs 工具读取），
 * 附件卡片是展示层补充 —— 气泡渲染缩略图/播放器/文件卡片，请求层经
 * AttachmentPayload 把二进制挂到 `_image`/`_audio_data` 键发给多模态模型。
 *
 * @property type "image" | "audio" | "video" | "document" | "file"
 * @property path 绝对路径（workspace/录音目录内），LLM fs 工具可读
 * @property format input_audio 格式: "m4a"/"mp3"/"wav"
 */
@Serializable
data class AttachmentData(
    val type: String,
    val path: String,
    val mimeType: String = "",
    val name: String = "",
    val size: Long = 0L,
    val durationMs: Long = 0L,
    val format: String = ""
)
