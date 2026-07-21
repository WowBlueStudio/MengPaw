// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.namespace

import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages screenshot file paths to avoid conflicts between concurrent agent sessions.
 */
object ScreenshotManager {
    private val counter = AtomicInteger(0)

    private const val MAX_SCREENSHOTS = 50
    private val SCREENSHOTS_DIR = com.mengpaw.kernel.DataPaths.SCREENSHOTS

    /**
     * Generate a unique screenshot path for a session.
     */
    fun generatePath(sessionId: String, suffix: String = ""): String {
        // FIX A13: Sanitize suffix to prevent path traversal (block ../ / ..\ etc.)
        val safeSuffix = suffix.replace(Regex("[/\\\\:\\.]"), "_").take(30)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val seq = counter.incrementAndGet()
        val dir = File(SCREENSHOTS_DIR)
        dir.mkdirs()
        return "${SCREENSHOTS_DIR}/${sessionId}_${timestamp}_${seq}${safeSuffix}.png"
    }

    /**
     * Clean up old screenshots for a session, keeping only the most recent N.
     */
    fun cleanup(sessionId: String, keep: Int = MAX_SCREENSHOTS) {
        val dir = File(SCREENSHOTS_DIR)
        if (!dir.exists()) return
        val files = dir.listFiles { it.name.startsWith(sessionId) }
            ?.sortedBy { it.name }
            ?: return
        if (files.size > keep) {
            files.take(files.size - keep).forEach { it.delete() }
        }
    }

    /**
     * Clean up ALL screenshots (call when agent session ends).
     */
    fun cleanupAll(sessionId: String) {
        val dir = File(SCREENSHOTS_DIR)
        if (!dir.exists()) return
        dir.listFiles { it.name.startsWith(sessionId) }?.forEach { it.delete() }
    }
}
