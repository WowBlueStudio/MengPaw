// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.agent.FleetCapability
import java.io.File

/**
 * 本机 (Android) 舰队能力卡收集 (v0.36) — 框架/环境/硬件/磁盘/开发环境。
 * 经 FleetCapabilityRegistry 注入内核, 响应指挥所 fleet.scan 请求。
 * PC 端 (MengPaw PC / Codex / Trae) 由各自环境提供, 本收集器仅 Android。
 */
object FleetCapabilityCollector {

    fun collectJson(): String {
        val base = com.mengpaw.kernel.DataPaths.BASE
        val diskFreeMB = try { File(base).usableSpace / 1024 / 1024 } catch (_: Exception) { 0L }
        val card = FleetCapability(
            frameworkName = "MengPaw (Android)",
            frameworkType = "mengpaw",
            version = com.mengpaw.kernel.AgentEngine.CORE_VERSION,
            environment = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            ramMB = Runtime.getRuntime().maxMemory() / 1024 / 1024,
            diskFreeMB = diskFreeMB,
            devTools = detectDevTools()
        )
        return card.toJson()
    }

    /** Android 端开发工具检测 — 常见目录探测, 探测不到即空 (PC 端才有完整工具链)。 */
    private fun detectDevTools(): List<String> {
        val found = mutableListOf<String>()
        // Termux 常见安装路径 — Android 上的类终端开发环境
        if (File("/data/data/com.termux/files/usr/bin").isDirectory) found.add("termux")
        return found
    }
}
