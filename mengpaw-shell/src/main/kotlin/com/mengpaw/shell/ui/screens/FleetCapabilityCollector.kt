// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.agent.FleetCapability
import java.io.File

/**
 * 本机 (Android) 舰队能力卡收集 (v0.36 平台化) — kernel 纯 JVM 基础 + Android 补充。
 * 桌面三端直接用 kernel FleetCapabilityCollector; Android 在此覆盖设备型号/API + Termux。
 */
object AndroidFleetCapabilityCollector {

    fun collectJson(): String =
        com.mengpaw.kernel.agent.FleetCapabilityCollector.collect(
            frameworkName = "MengPaw (Android)",
            environment = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            extraDevTools = detectAndroidTools()
        ).toJson()

    /** Android 特有工具检测 — Termux 常见路径, 探测不到即空。 */
    private fun detectAndroidTools(): List<String> {
        val found = mutableListOf<String>()
        if (java.io.File("/data/data/com.termux/files/usr/bin").isDirectory) found.add("termux")
        return found
    }
}
