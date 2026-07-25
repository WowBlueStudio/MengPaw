// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.root

import java.io.File

/**
 * Root / Magisk detection for Android.
 *
 * Checks multiple indicators:
 * 1. su binary in common paths
 * 2. Magisk daemon socket
 * 3. System properties (ro.build.tags, ro.debuggable)
 * 4. SELinux status
 * 5. BusyBox presence
 */
object RootDetector {

    /** Known su binary paths. */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/su/bin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/system/bin/failsafe/su",
        "/system/xbin/mu",       // Magisk su (modern)
        "/magisk/.core/bin/su"   // Magisk legacy
    )

    /** Magisk-specific indicators. */
    private val MAGISK_PATHS = listOf(
        "/sbin/magisk",
        "/data/adb/magisk",
        "/data/adb/modules",
        "/data/adb/magisk.db",
        "/cache/.magisk",
    )

    data class RootStatus(
        val isRooted: Boolean,
        val suPath: String?,
        val suVersion: String?,
        val isMagisk: Boolean,
        val magiskVersion: String?,
        val selinux: String,
        val hasBusybox: Boolean,
        val buildTags: String,
        val isDebuggable: Boolean
    ) {
        val summary: String get() = buildString {
            appendLine("## Root 状态")
            appendLine("- Root: ${if (isRooted) "✅ 已获取" else "❌ 未 Root"}")
            if (suPath != null) appendLine("- su: $suPath")
            if (suVersion != null) appendLine("- su 版本: $suVersion")
            appendLine("- Magisk: ${if (isMagisk) "✅ ${magiskVersion ?: ""}" else "❌ 未安装"}")
            appendLine("- SELinux: $selinux")
            appendLine("- BusyBox: ${if (hasBusybox) "✅" else "❌"}")
            appendLine("- Build Tags: $buildTags")
            appendLine("- Debuggable: ${if (isDebuggable) "是" else "否"}")
        }
    }

    fun detect(): RootStatus {
        var suPath: String? = null
        var suVersion: String? = null
        var isMagisk = false
        var magiskVersion: String? = null
        var hasBusybox = false

        // 1. Check su binaries
        for (path in SU_PATHS) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                suPath = path
                break
            }
        }
        // Also try which su
        if (suPath == null) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
                val out = p.inputStream.bufferedReader().readText().trim()
                if (out.isNotBlank()) suPath = out
                p.waitFor()
            } catch (_: Exception) {}
        }

        // 2. Check Magisk
        for (path in MAGISK_PATHS) {
            if (File(path).exists()) { isMagisk = true; break }
        }
        if (isMagisk) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "magisk -c"))
                val out = p.inputStream.bufferedReader().readText().trim()
                if (out.isNotBlank()) magiskVersion = out
                p.waitFor()
            } catch (_: Exception) {}
            if (magiskVersion == null) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "magisk -v"))
                    magiskVersion = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor()
                } catch (_: Exception) {}
            }
        }

        // 3. Get su version
        if (suPath != null && suVersion == null) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "--version"))
                suVersion = p.inputStream.bufferedReader().readText().trim().lines().firstOrNull()
                p.waitFor()
            } catch (_: Exception) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-v"))
                    suVersion = p.errorStream.bufferedReader().readText().trim().lines().firstOrNull()
                        ?: p.inputStream.bufferedReader().readText().trim().lines().firstOrNull()
                    p.waitFor()
                } catch (_: Exception) {}
            }
        }

        // 4. Check BusyBox
        try {
            val p = Runtime.getRuntime().exec(arrayOf("busybox"))
            hasBusybox = p.waitFor() == 0 || p.inputStream.bufferedReader().readText().isNotBlank()
        } catch (_: Exception) {}

        // 5. Get system properties
        val buildTags = try {
            Runtime.getRuntime().exec(arrayOf("getprop", "ro.build.tags"))
                .inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }
        val isDebuggable = try {
            val v = Runtime.getRuntime().exec(arrayOf("getprop", "ro.debuggable"))
                .inputStream.bufferedReader().readText().trim()
            v == "1"
        } catch (_: Exception) { false }

        // 6. SELinux
        val selinux = try {
            File("/sys/fs/selinux/enforce").readText().trim().let {
                when (it) { "1" -> "Enforcing"; "0" -> "Permissive"; else -> "Unknown" }
            }
        } catch (_: Exception) { "Unknown" }

        return RootStatus(
            isRooted = suPath != null,
            suPath = suPath,
            suVersion = suVersion,
            isMagisk = isMagisk,
            magiskVersion = magiskVersion,
            selinux = selinux,
            hasBusybox = hasBusybox,
            buildTags = buildTags,
            isDebuggable = isDebuggable
        )
    }
}
