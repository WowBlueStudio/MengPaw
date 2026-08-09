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
        // 旧私有输出目录 — /Android/data/<pkg>/files/output/ (Android 11+ 文件管理器隐藏)
        val legacy = context.getExternalFilesDir("output")?.absolutePath
            ?: "${context.filesDir.parentFile?.absolutePath}/files/output"
        // v0.34.3: 公共输出目录 /storage/emulated/0/MengPaw/ — 用户可见, 去掉无用的
        // android/data/com.mengpaw.shell/ 路径。需"所有文件访问"授权 (MANAGE_EXTERNAL_STORAGE);
        // 未授权/写入失败时回退旧私有目录, agent.output 会提示授权。
        var output = legacy
        val publicOutput = try {
            java.io.File(android.os.Environment.getExternalStorageDirectory(), "MengPaw")
        } catch (_: Exception) { null }
        if (publicOutput != null) {
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
            } catch (_: Exception) {
                output = legacy // 公共目录不可写 → 回退私有 (写失败静默, agent.output 会提示)
            }
        }
        DataPaths.initializeOutput(output)
    }
}
