// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.device.AgentEntry
import com.mengpaw.kernel.device.DeviceCollectors
import com.mengpaw.kernel.device.nowIso

/**
 * 设备库存的 agent 能力事实 (P4, 设计 §7.4)。
 *
 * kernel 的 [DeviceCollectors] 只持有硬件采集器与"agent 事实"两个注入点 —— 硬件由宿主
 * (Android `DeviceCollectorImpl`) 提供, 模型/上下文/视觉/插件由本插件提供 (它持有
 * `ModelProfileResolver` 与本插件清单, kernel 不引 LLM)。
 *
 * 每次调用重新解析画像 (分辨率高但便宜), `observedAt` 取当前时间 → 库存合并时本机这条总是最新;
 * 而"有差异才写"判定会忽略时间戳 (见 `DeviceCommands.refreshLocal`), 所以不会因时间刷新而刷历史。
 */
internal object TwinInventoryFacts {

    fun register() {
        DeviceCollectors.agentFactsProvider = {
            try {
                val profile = ModelProfileResolver.resolve(MemoryTwinPlugin.llmProvider, MemoryTwinPlugin.agentName)
                listOf(
                    AgentEntry(
                        name = MemoryTwinPlugin.agentName,
                        model = profile.modelName,
                        contextTokens = profile.contextWindowTokens,
                        vision = if (profile.visionSource == CapabilitySource.UNKNOWN) null else profile.supportsVision,
                        tools = if (profile.toolsSource == CapabilitySource.UNKNOWN) null else profile.supportsTools,
                        plugins = MemoryTwinPlugin.pluginNames,
                        observedAt = nowIso(),
                        sources = buildMap {
                            put("model", profile.qualitySource.name.lowercase())
                            put("context", profile.ctxSource.name.lowercase())
                            if (profile.visionSource != CapabilitySource.UNKNOWN) {
                                put("vision", profile.visionSource.name.lowercase())
                            }
                        }
                    )
                )
            } catch (e: Exception) {
                com.mengpaw.kernel.error.ErrorCollector.report(e, "TwinInventoryFacts")
                emptyList()
            }
        }
    }
}
