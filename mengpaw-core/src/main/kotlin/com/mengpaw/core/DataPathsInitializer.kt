// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core

import android.content.Context
import com.mengpaw.kernel.DataPaths

/**
 * Android bridge: initializes kernel's DataPaths with the app's files directory.
 * Call in Application.onCreate() or MainActivity.onCreate() before any kernel operations.
 */
object DataPathsInitializer {
    fun initialize(context: Context) {
        DataPaths.initialize(context.filesDir.absolutePath)
        resolveAndApplyOutput(context, migrateLegacy = true)
    }

    /**
     * 授权后重新探测输出目录 (v0.35.1) — 启动时未授权回退私有目录,
     * 用户在系统设置授予『所有文件访问』后, 经 MainActivity.onResume 调用,
     * 输出目录实时切到公共 /storage/emulated/0/MengPaw/。
     */
    fun refreshOutput(context: Context) {
        resolveAndApplyOutput(context, migrateLegacy = false)
    }

    /** 探测公共输出目录可写性并应用 — 成功用公共, 失败保留当前 (或首次回退私有)。 */
    private fun resolveAndApplyOutput(context: Context, migrateLegacy: Boolean) {
        // 旧私有输出目录 — /Android/data/<pkg>/files/output/ (Android 11+ 文件管理器隐藏)
        val legacy = context.getExternalFilesDir("output")?.absolutePath
            ?: "${context.filesDir.parentFile?.absolutePath}/files/output"
        val current = com.mengpaw.kernel.DataPaths.OUTPUT
        // v0.34.3: 公共输出目录 /storage/emulated/0/MengPaw/ — 用户可见, 去掉无用的
        // android/data/com.mengpaw.shell/ 路径。需"所有文件访问"授权 (MANAGE_EXTERNAL_STORAGE);
        // 未授权/写入失败时回退旧私有目录, agent.output 会提示授权。
        var output = if (migrateLegacy) legacy else current
        val publicOutPath = try {
            java.io.File(android.os.Environment.getExternalStorageDirectory(), "MengPaw").absolutePath
        } catch (_: Exception) { null }
        if (publicOutPath != null && current != publicOutPath) {
            val publicOutput = java.io.File(publicOutPath)
            try {
                if (publicOutput.exists() || publicOutput.mkdirs()) {
                    val probe = java.io.File(publicOutput, ".mengpaw_write_probe")
                    probe.writeText("ok")
                    probe.delete()
                    output = publicOutput.absolutePath
                    // 一次性迁移旧私有目录文件 → 公共目录 (仅未冲突文件)
                    val old = java.io.File(legacy)
                    if (old.isDirectory) {
                        old.listFiles()?.forEach { f ->
                            if (f.isFile) {
                                val target = java.io.File(publicOutput, f.name)
                                if (!target.exists()) {
                                    try { f.copyTo(target, overwrite = false) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) { /* 公共目录不可写 → 保留 output 默认 (legacy/current) */ }
        }
        DataPaths.initializeOutput(output)
    }
}
