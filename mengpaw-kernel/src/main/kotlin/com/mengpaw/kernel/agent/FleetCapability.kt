// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 舰队能力卡 (v0.36) — 指挥所理解框架能力边界的依据:
 * 框架名称/类型/版本、所在环境、硬件 (CPU/内存/磁盘)、开发环境。
 * 经 ACP `FLEET_CAPABILITY` 上报, 指挥所 `fleet.scan` 收集后写入 Notes。
 */
@Serializable
data class FleetCapability(
    val frameworkName: String,
    val frameworkType: String,
    val version: String,
    val environment: String,
    val deviceName: String,
    val cpuCores: Int,
    val ramMB: Long,
    val diskFreeMB: Long,
    val devTools: List<String> = emptyList(),
    val reportedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun fromJson(raw: String): FleetCapability? = try {
            json.decodeFromString<FleetCapability>(raw)
        } catch (_: Exception) { null }

        /** 收集到的能力卡缓存 (指挥所 scan 期间) — 纯内存, 持久化靠 Notes 落盘。 */
        val cache = ConcurrentHashMap<String, String>()

        /** 能力卡列表 → Notes Markdown (纯函数, 供 fleet.scan 落盘)。 */
        fun formatNotes(caps: Map<String, String>): String {
            if (caps.isEmpty()) return "暂无舰队能力上报 — 执行 `fleet.scan` 收集。"
            return buildString {
                appendLine("# 舰队能力清单")
                appendLine("> 收集时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine()
                caps.forEach { (peerId, json) ->
                    val c = fromJson(json)
                    if (c != null) {
                        appendLine("## ${c.frameworkName} (${c.frameworkType})")
                        appendLine("- 节点: $peerId · 版本: ${c.version}")
                        appendLine("- 环境: ${c.environment} · 设备: ${c.deviceName}")
                        appendLine("- 硬件: ${c.cpuCores} 核 · 内存 ${c.ramMB}MB · 磁盘剩余 ${c.diskFreeMB}MB")
                        if (c.devTools.isNotEmpty()) appendLine("- 开发环境: ${c.devTools.joinToString(" / ")}")
                        appendLine()
                    } else {
                        appendLine("## $peerId (解析失败)")
                        appendLine()
                    }
                }
            }.trimEnd()
        }
    }
}
