// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import java.io.File

// ── 网络下载缓存 (手写 HttpURLConnection, 零依赖) ──
// 拆自 AttachmentBubbles.kt (2026-08-06, >400 行文件拆分批次4)。

/** 下载远程媒体到 cacheDir/media_cache (已有缓存则复用), 返回本地路径或 null。 */
internal fun downloadToCache(context: android.content.Context, url: String): String? {
    return try {
        val dir = File(context.cacheDir, "media_cache")
        dir.mkdirs()
        val ext = url.substringAfterLast('.', "").take(5).filter { it.isLetterOrDigit() }
        val fileName = sha1(url) + if (ext.isNotBlank()) ".$ext" else ".bin"
        val file = File(dir, fileName)
        if (file.exists() && file.length() > 0) return file.absolutePath
        val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 MengPaw/0.33")
        }
        conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output, 64 * 1024) } }
        conn.disconnect()
        if (file.exists() && file.length() > 0) file.absolutePath else null
    } catch (_: Exception) { null }
}

internal fun sha1(s: String): String =
    java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
        .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
