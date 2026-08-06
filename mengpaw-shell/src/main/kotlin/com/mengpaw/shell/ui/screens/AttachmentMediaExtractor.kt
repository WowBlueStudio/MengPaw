// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.typeFromMime
import java.io.File

// ── 下行媒体提取: LLM 文本 → 清理后文本 + 附件卡片 ─────────────────
// 拆自 AttachmentBubbles.kt (2026-08-06, >400 行文件拆分批次4)。
//
// 提取规则 (v0.33.0+):
// 1. `![alt](path)` markdown 图片 → 卡片 (data:/javascript: 前缀排除)
// 2. `[name](path)` 链接且扩展名命中媒体/pdf/doc/xls/zip → 卡片
// 3. `Saved to <path>` / `已保存到 <path>` 行 (render/comfy 插件输出格式) → 卡片 (路径须存在)
// 本地路径必须 exists 才提取 (保守); http(s) URL 按扩展名判定 (无法预验证)

/** 提取媒体引用, 返回 (清理后文本, 卡片列表)。 */
fun extractMedia(content: String): Pair<String, List<AttachmentData>> {
    val cards = mutableListOf<AttachmentData>()
    var text = content

    // 1. ![alt](path)
    text = MARKDOWN_IMAGE_REGEX.replace(text) { m ->
        val path = m.groupValues[1].trim()
        val card = mediaFromMarkdownPath(path) { typeFromMime(null, path) }
        if (card != null) { cards.add(card); "" } else m.value
    }
    // 2. [name](path) — 仅扩展名命中媒体/document 才提取
    //     (审查修复): 本地路径必须 exists 才提取 — 与文件头"保守"规则及分支 1 行为对齐,
    //     否则 ![x](已删除文件.png) 会经链接分支漏出幻影卡片 (UI 渲染坏图)
    text = LINK_REGEX.replace(text) { m ->
        val name = m.groupValues[1].trim()
        val path = m.groupValues[2].trim()
        val ext = path.substringAfterLast('.', "").lowercase()
        val type = typeFromMime(null, path)
        val isRemote = path.startsWith("http://") || path.startsWith("https://")
        val keep = type in setOf("image", "audio", "video", "document") &&
            !path.startsWith("data:") && !path.startsWith("javascript:") &&
            (isRemote || File(path).isFile)
        if (keep && name != path && name.isNotBlank() && name.length <= 60) {
            cards.add(
                AttachmentData(
                    type = type, path = path, name = name,
                    mimeType = mimeForExt(ext)
                )
            ); ""
        } else m.value
    }
    // 3. Saved to / 已保存到 行 (插件输出)
    text = text.lines().joinToString("\n") { line ->
        val m = SAVED_TO_REGEX.find(line) ?: return@joinToString line
        val path = m.groupValues[1].trim()
        val card = mediaFromMarkdownPath(path) { typeFromMime(null, path) }
        if (card != null) { cards.add(card); "" } else line
    }
    return text to cards
}

/** markdown 图片/路径 → 附件卡片; 本地路径须存在, URL 按扩展名。 */
internal fun mediaFromMarkdownPath(path: String, typeOf: (String) -> String): AttachmentData? {
    val trimmed = path.trim().removePrefix("file://")
    if (trimmed.startsWith("data:") || trimmed.startsWith("javascript:") || trimmed.isBlank()) return null
    val isRemote = trimmed.startsWith("http://") || trimmed.startsWith("https://")
    if (!isRemote) {
        val f = File(trimmed)
        if (!f.exists() || !f.isFile) return null
    }
    val type = typeOf(trimmed)
    if (type !in setOf("image", "audio", "video", "document")) return null
    return AttachmentData(
        type = type, path = trimmed, name = trimmed.substringAfterLast('/'),
        mimeType = mimeForExt(trimmed.substringAfterLast('.', "").lowercase()),
        size = if (isRemote) 0L else File(trimmed).length()
    )
}

internal val LINK_REGEX = Regex("\\[([^\\]]{1,80})]\\(([^)]{1,500})\\)")
internal val SAVED_TO_REGEX = Regex("(?:Saved to|已保存到)\\s+(\\S+)\\s*$")

internal fun mimeForExt(ext: String): String = when (ext) {
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
    "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"
    "pdf" -> "application/pdf"; "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "zip" -> "application/zip"; "txt" -> "text/plain"; "md" -> "text/markdown"
    else -> "application/octet-stream"
}
