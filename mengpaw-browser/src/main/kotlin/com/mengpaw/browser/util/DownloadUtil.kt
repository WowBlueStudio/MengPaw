// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ── Download ──────────────────────────────────────────────────────

// FIX U50: Accept CoroutineScope for lifecycle-aware cancellation; ensure cleanup
fun downloadImage(scope: CoroutineScope, ctx: android.content.Context, url: String) {
    scope.launch(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Chrome/120.0.0.0")
            conn.setRequestProperty("Referer", url)
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
            if (bmp != null) {
                val name = url.substringAfterLast('/').substringBefore('?').take(100)
                    .ifBlank { "img_${System.currentTimeMillis()}" }
                val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "MengPaw")
                dir.mkdirs()
                val file = File(dir, name)
                file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                withContext(Dispatchers.Main) {
                    android.media.MediaScannerConnection.scanFile(
                        ctx, arrayOf(file.absolutePath), null, null
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserActivity", "Image download failed", e)
        } finally {
            conn?.disconnect()
        }
    }
}
