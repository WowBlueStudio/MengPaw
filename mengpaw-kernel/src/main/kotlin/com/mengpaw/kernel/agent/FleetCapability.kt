// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.device.DeviceInventory
import com.mengpaw.kernel.device.DeviceInventoryStore
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

        /**
         * 能力卡列表 → Notes Markdown (纯函数, 供 fleet.scan 落盘)。
         *
         * P4.5 (设计 §7.5): **库存优先** — [inventory] 非空时按库存行渲染 (离线端也在列,
         * 每设备一节含名称/形态/模型/CPU/内存/关键能力/更新时间); 库存为空才回退
         * 本次扫描收到的内存能力卡 (保持旧行为)。
         */
        fun formatNotes(caps: Map<String, String>, inventory: List<DeviceInventory> = emptyList()): String {
            if (inventory.isNotEmpty()) return formatInventoryNotes(inventory)
            if (caps.isEmpty()) return "暂无舰队能力上报 — 执行 `fleet.scan` 收集。"
            return buildString {
                appendLine("# 舰队能力清单")
                appendLine("> 收集时间: ${collectTime()}")
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

        /** 库存行 → Notes (P4.5)。输出顺序按显示名, 保证跨端同内容同文本。 */
        private fun formatInventoryNotes(rows: List<DeviceInventory>): String = buildString {
            appendLine("# 舰队能力清单 (来源: 设备库存)")
            appendLine("> 生成时间: ${collectTime()}")
            appendLine()
            rows.sortedBy { it.displayName }.forEach { inv ->
                val form = inv.formFactor.ifBlank { DeviceInventory.FORM_UNKNOWN }
                appendLine("## ${inv.displayName} ($form)")
                appendLine("- 设备: ${inv.deviceId} · 形态: $form")
                appendLine("- 模型: ${modelText(inv)}")
                appendLine(
                    "- 硬件: CPU ${inv.cpuCores} 核 · 内存 ${inv.ramGBText()} · " +
                        "磁盘剩余 ${inv.storageFreeMB}MB"
                )
                appendLine("- 关键能力: ${inv.capabilities().joinToString(" / ").ifBlank { "无登记" }}")
                appendLine(
                    "- 更新时间: ${DeviceInventoryStore.ageText(inv)}" +
                        " (${inv.observedAt.ifBlank { "无时间记录" }})"
                )
                appendLine()
            }
        }.trimEnd()

        /** 模型段: `模型名 · 上下文 NK`; 无登记 → `无登记`。 */
        private fun modelText(inv: DeviceInventory): String {
            val agent = inv.agents.firstOrNull { it.model.isNotBlank() } ?: return "无登记"
            val ctx = if (agent.contextTokens > 0) " · 上下文 ${agent.contextTokens / 1000}K" else ""
            return agent.model + ctx
        }

        private fun collectTime(): String = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
        ).format(java.util.Date())
    }
}
