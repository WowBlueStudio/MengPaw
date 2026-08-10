// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import java.io.File

/**
 * 跨平台能力卡采集 (v0.36 平台化) — 纯 JVM 基础实现, 任何跑 kernel 的端直接可用:
 * OS/CPU/内存/磁盘剩余/开发工具链 (PATH 探测)。平台特有信息 (Android 设备型号/API、
 * 桌面端发行版等) 由各端在 [FleetPlatform.capabilityProvider] 里补充覆盖。
 *
 * 桌面三端 (Win/OSX/Linux) 可直接注册 `FleetCapabilityCollector::collectJson`。
 */
object FleetCapabilityCollector {

    fun collectJson(): String = collect().toJson()

    fun collect(
        frameworkName: String = "MengPaw",
        environment: String = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        deviceName: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        extraDevTools: List<String> = emptyList()
    ): FleetCapability {
        val diskFreeMB = try { File(com.mengpaw.kernel.DataPaths.BASE).usableSpace / 1024 / 1024 } catch (_: Exception) { 0L }
        return FleetCapability(
            frameworkName = frameworkName,
            frameworkType = "mengpaw",
            version = com.mengpaw.kernel.AgentEngine.CORE_VERSION,
            environment = environment,
            deviceName = deviceName,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            ramMB = Runtime.getRuntime().maxMemory() / 1024 / 1024,
            diskFreeMB = diskFreeMB,
            devTools = (detectPathTools() + extraDevTools).distinct()
        )
    }

    /** PATH 工具链探测 (纯 JVM) — Windows 后缀 .exe, Unix 直接可执行。 */
    private fun detectPathTools(): List<String> {
        val path = System.getenv("PATH") ?: return emptyList()
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() }
        val candidates = listOf("node", "npm", "python3", "python", "git", "java", "javac",
            "gradle", "go", "rustc", "docker", "adb", "kotlinc")
        return candidates.filter { tool ->
            val exe = if (isWindows) "$tool.exe" else tool
            dirs.any { dir -> File(dir, exe).canExecute() }
        }
    }
}
