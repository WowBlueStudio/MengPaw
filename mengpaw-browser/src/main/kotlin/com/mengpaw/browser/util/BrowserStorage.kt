// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import android.os.Build
import android.os.Environment
import java.io.File

/**
 * 浏览器公共存储 — 截图/输出落盘公共目录（半自动武器方案决策 #2/#6）。
 *
 * 浏览器进程的 DataPaths.BASE 是私有 filesDir（Agent 在 Shell 进程读不到），
 * 截图必须落盘 `/storage/emulated/0/MengPaw/截图存档`（MANAGE_EXTERNAL_STORAGE
 * 授权后 Shell 侧 agent.read / Linux 管道才能读回）。
 *
 * - [hasStorageAccess]: API 30+ 检查 MANAGE_EXTERNAL_STORAGE；旧版本恒 true。
 * - [screenshotsDir]: 公共目录可写时返回公共路径，否则回退私有 DataPaths.SCREENSHOTS
 *   （拒绝授权后的降级路径，命令结果会提示重新授权）。
 */
object BrowserStorage {

    /** 公共基础目录 — 与 Shell 侧 DataPathsInitializer 的公共输出目录对齐。 */
    const val PUBLIC_BASE: String = "/storage/emulated/0/MengPaw"

    /** 公共截图存档目录 — Agent 可读（决策 #5 分段截图也落此目录）。 */
    val PUBLIC_SCREENSHOTS: String get() = "$PUBLIC_BASE/截图存档"

    /** 是否已授予"所有文件访问"（MANAGE_EXTERNAL_STORAGE）。 */
    fun hasStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            Environment.isExternalStorageManager()
        } catch (_: Exception) {
            false
        }
    }

    /** 当前生效的截图目录：公共优先，不可写回退私有（拒绝降级，决策 #6）。 */
    fun screenshotsDir(): String {
        if (hasStorageAccess()) {
            val dir = File(PUBLIC_SCREENSHOTS)
            try {
                if (dir.exists() || dir.mkdirs()) return PUBLIC_SCREENSHOTS
            } catch (_: Exception) {
                // 公共目录不可写 → 回退私有
            }
        }
        return com.mengpaw.kernel.DataPaths.SCREENSHOTS
    }
}
