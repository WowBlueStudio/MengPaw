// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared helper functions extracted from [SysExecutor].
 *
 * These are used across domain executor objects and kept here to avoid duplication.
 */

/** Check if a permission has been granted. Works on all API levels. */
internal fun Context.checkSelf(permission: String): Boolean =
    if (Build.VERSION.SDK_INT >= 23) {
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    } else true

/** Map file extension to MIME type for intent viewing. */
internal fun mimeTypeFor(filename: String): String = when {
    filename.endsWith(".pdf") -> "application/pdf"
    filename.endsWith(".png") -> "image/png"
    filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
    filename.endsWith(".gif") -> "image/gif"
    filename.endsWith(".mp4") -> "video/mp4"
    filename.endsWith(".mp3") -> "audio/mpeg"
    filename.endsWith(".apk") -> "application/vnd.android.package-archive"
    filename.endsWith(".txt") || filename.endsWith(".md") -> "text/plain"
    filename.endsWith(".html") -> "text/html"
    else -> "*/*"
}

/** Format storage space (internal/external) into human-readable string. */
internal fun formatStorage(dir: File): String {
    return try {
        val stat = StatFs(dir.path)
        val total = stat.totalBytes shr 30
        val free = stat.availableBytes shr 30
        "$free / $total GB free"
    } catch (_: Exception) { "unavailable" }
}

/** Parse a time string — accepts "yyyy-MM-dd HH:mm" or Unix millis (13+ digits). */
internal fun parseTime(s: String): Long? {
    return try {
        if (s.matches(Regex("\\d{13,}"))) s.toLong() // Unix ms
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(s)?.time
    } catch (_: Exception) { null }
}

/** Format millis to "MM-dd HH:mm" for display. */
internal fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
