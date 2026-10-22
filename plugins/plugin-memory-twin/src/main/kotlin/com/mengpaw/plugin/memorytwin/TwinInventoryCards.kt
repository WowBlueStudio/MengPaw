// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.device.AgentEntry
import com.mengpaw.kernel.device.DeviceInventory
import com.mengpaw.kernel.device.DeviceInventoryStore

/**
 * 库存行 → [CapabilityCard] 适配 (P4, 设计 §7.5/§7.6)。
 *
 * 能力卡降级为**库存的传输信封**: 本对象只负责把 `devices/<deviceId>.md` 的库存字段装进信封,
 * 让既有路由打分逻辑 (`TwinRouter.scorePeer`) 得以复用, 同时让**离线设备也能参与评估**
 * (不再只认本次会话收到过能力卡上报的节点)。
 *
 * 运行时字段 (在线/电量/忙闲) 库存里没有 → 一律给"未知"取值:
 * `batteryLevel = -1` (不误触发低电扣分) / `networkType = ""` (不误判移动网络)。
 */
internal object TwinInventoryCards {

    /** 全部库存行 (读不到 → 空表, 调用方回退内存能力卡)。 */
    fun all(): List<DeviceInventory> = DeviceInventoryStore.readAll()

    fun toCard(inv: DeviceInventory): CapabilityCard {
        val agent = pickAgent(inv)
        return CapabilityCard(
            deviceId = inv.deviceId,
            deviceName = inv.displayName,
            deviceModel = inv.os,
            formFactor = formFactorOf(inv.formFactor),
            hardware = HardwareProfile(
                cpuCores = inv.cpuCores,
                ramTotalMB = inv.ramMB,
                storageFreeMB = inv.storageFreeMB,
                hasCamera = inv.camera.isNotEmpty(),
                cameraFacing = inv.camera,
                hasBluetooth = false,
                hasNfc = false,
                sensors = inv.sensors,
                screenWidth = screenOf(inv.screen).first,
                screenHeight = screenOf(inv.screen).second,
                batteryLevel = -1,
                isCharging = false,
                networkType = ""
            ),
            model = modelOf(agent),
            software = SoftwareProfile(
                mengpawVersion = "",
                installedPlugins = inv.agents.flatMap { it.plugins }.distinct(),
                optionalCapabilities = inv.agents.flatMap { it.plugins }.distinct(),
                grantedPermissions = emptyList()
            ),
            runtime = RuntimeStatus(
                isOnline = false,
                uptimeSeconds = 0,
                lastSeenAt = DeviceInventoryStore.observedInstant(inv.observedAt)?.toEpochMilli() ?: 0L,
                currentSessionId = null,
                isBusy = false
            ),
            protocolVersion = TwinSyncV3.PROTOCOL_VERSION,
            schemaVersion = inv.schemaVersion
        )
    }

    /** 优先取本 agent 的登记, 否则取最新观测的一条 (都无 → 空画像)。 */
    private fun pickAgent(inv: DeviceInventory): AgentEntry? =
        inv.agents.firstOrNull { it.name == MemoryTwinPlugin.agentName }
            ?: inv.agents.maxByOrNull { it.observedAt }

    /**
     * 模型画像 — 只搬**有来源**的判定: 上下文来自声明的 `contextTokens`, 视觉/工具按
     * `vision/tools` 是否为 null 决定"已知/未知"。档位（quality）库存里没有 → UNKNOWN 中性,
     * 不做猜测 (与 2026-09-10 进化"未知不扣分"一致)。
     */
    private fun modelOf(agent: AgentEntry?): ModelProfile {
        if (agent == null) {
            return ModelProfile(
                providerName = "", modelName = "unknown", providerType = "",
                contextWindowTokens = 0, supportsVision = false, supportsTools = false,
                estimatedQuality = ModelQuality.UNKNOWN
            )
        }
        return ModelProfile(
            providerName = "",
            modelName = agent.model.ifBlank { "unknown" },
            providerType = "",
            contextWindowTokens = agent.contextTokens,
            supportsVision = agent.vision == true,
            supportsTools = agent.tools == true,
            estimatedQuality = ModelQuality.UNKNOWN,
            qualitySource = CapabilitySource.UNKNOWN,
            ctxSource = if (agent.contextTokens > 0) CapabilitySource.BUILTIN else CapabilitySource.UNKNOWN,
            visionSource = if (agent.vision == null) CapabilitySource.UNKNOWN else CapabilitySource.BUILTIN,
            toolsSource = if (agent.tools == null) CapabilitySource.UNKNOWN else CapabilitySource.BUILTIN,
            evidenceCount = 0,
            matchedRuleId = null
        )
    }

    private fun formFactorOf(raw: String): FormFactor =
        FormFactor.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: FormFactor.UNKNOWN

    /** `2560x1600@320` → (2560, 1600); 解析失败 → (0, 0)。 */
    private fun screenOf(raw: String): Pair<Int, Int> {
        val core = raw.substringBefore('@').trim()
        val parts = core.split('x', 'X')
        if (parts.size != 2) return 0 to 0
        return (parts[0].trim().toIntOrNull() ?: 0) to (parts[1].trim().toIntOrNull() ?: 0)
    }
}
