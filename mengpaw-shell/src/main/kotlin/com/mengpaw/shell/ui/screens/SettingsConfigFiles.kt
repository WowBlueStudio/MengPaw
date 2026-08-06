// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import java.io.File

// ── CONFIG 目录布尔/枚举偏好读写 — 拆自 SettingsViewModel.kt (2026-08-06, 批次4) ──
// 统一 readText/writeText, 原 ViewModel 各处 try/catch + mkdirs + trim 行为对齐。

internal object SettingsConfigFiles {

    /** 读取 CONFIG 下配置文本 (已 trim); 文件不存在返回 null。 */
    fun readText(name: String): String? {
        val f = File(com.mengpaw.kernel.DataPaths.CONFIG, name)
        return if (f.exists()) f.readText().trim() else null
    }

    /** 写 CONFIG 下配置文本 (自动建目录)。 */
    fun writeText(name: String, value: String) {
        val f = File(com.mengpaw.kernel.DataPaths.CONFIG, name)
        f.parentFile?.mkdirs()
        f.writeText(value)
    }
}
