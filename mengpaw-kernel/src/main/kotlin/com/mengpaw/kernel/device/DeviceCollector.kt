// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

import com.mengpaw.kernel.error.ErrorCollector
import java.time.OffsetDateTime

/**
 * 采集器 SPI (docs/memory-git-sync-design.md §7.4) — 纯 JVM 接口, 平台实现在宿主侧注册。
 *
 * 仿 [com.mengpaw.kernel.agent.FleetPlatform.capabilityProvider] 注入模式:
 * Android 在 `MainActivity.onCreate` 注册读 `Build`/`SensorManager`/`CameraManager`/
 * `BatteryManager`/`DisplayMetrics` 的实现; 桌面端可只填 Build 等价信息。kernel 不引 android.*。
 */
interface DeviceCollector {
    /** 采集本机硬件快照; 失败返回 null (命令降级, 不抛)。 */
    fun collect(): DeviceSnapshot?
}

/**
 * 硬件快照 — 只含**库存字段** (§7.3): 电量/在线/忙闲属运行时, 走心跳, 不进库存。
 * [batteryLevel]/[isCharging] 仅用于 `device.self` 展示实时状态, 不落库存 (否则每次刷新都产生差异)。
 */
data class DeviceSnapshot(
    val deviceName: String,
    val formFactor: String = DeviceInventory.FORM_UNKNOWN,
    val os: String = "",
    val cpuCores: Int = 0,
    val ramMB: Long = 0L,
    val storageFreeMB: Long = 0L,
    /** `宽x高@dpi`。 */
    val screen: String = "",
    val sensors: List<String> = emptyList(),
    val camera: List<String> = emptyList(),
    /** 运行时电量 (-1 = 未知) — 仅供展示，不入库存。 */
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false
)

/** 采集器注册表 — 平台实现在宿主启动时写入; 未注册时库存命令只读不写。 */
object DeviceCollectors {

    @Volatile
    var collector: DeviceCollector? = null

    /** agent 能力事实 (模型/上下文/视觉/工具/插件) — 由 plugin-memory-twin 注册 (kernel 不引 LLM)。 */
    @Volatile
    var agentFactsProvider: (() -> List<AgentEntry>)? = null

    /** 采集一次; 未注册或实现抛错 → null。 */
    fun snapshot(): DeviceSnapshot? = try {
        collector?.collect()
    } catch (e: Exception) {
        ErrorCollector.report(e, "DeviceCollectors.snapshot")
        null
    }

    /** 当前 agent 事实 (未注册 → 空表)。 */
    fun agentFacts(): List<AgentEntry> = try {
        agentFactsProvider?.invoke().orEmpty()
    } catch (e: Exception) {
        ErrorCollector.report(e, "DeviceCollectors.agentFacts")
        emptyList()
    }

    /** 测试复位 (生产代码不调用)。 */
    fun reset() {
        collector = null
        agentFactsProvider = null
    }
}

/** 快照 → 库存行 (字段一一对应; 缺字段给默认值)。 */
fun DeviceSnapshot.toInventory(
    deviceId: String,
    legacyFingerprint: String = deviceId,
    agentFacts: List<AgentEntry> = emptyList(),
    observedAt: String = nowIso()
): DeviceInventory = DeviceInventory(
    deviceId = deviceId,
    legacyFingerprint = legacyFingerprint,
    deviceName = deviceName,
    formFactor = formFactor,
    os = os,
    cpuCores = cpuCores,
    ramMB = ramMB,
    storageFreeMB = storageFreeMB,
    screen = screen,
    sensors = sensors,
    camera = camera,
    observedAt = observedAt,
    agents = agentFacts
)

/** 当前时间 (ISO-8601 带偏移, 与 §7.2 的 `observedAt` 同形态)。 */
fun nowIso(): String = OffsetDateTime.now().withNano(0).toString()
