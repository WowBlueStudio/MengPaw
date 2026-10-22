// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

/**
 * 设备库存行 (docs/memory-git-sync-design.md §7.2) — `{AGENTS}/twin/devices/<deviceId>.md` 的模型。
 *
 * **单写者铁律**: 每个端只写自己那一行 (见 [DeviceInventoryStore.write])。行键 = 现用 `deviceId`
 * (`AcpCrypto.myFingerprint()` 的 32 字符指纹, 由 P2 起沿用)。
 *
 * TODO(P5): 身份改 UUID 需与配对迁移同批 — 文件键/信任键/legacyFingerprint 三处一起换,
 * 单独改文件名等于让既有配对全部失效。
 *
 * 字段分两层 (§7.3): **库存** (进 Git: 型号/形态/OS/CPU/RAM/磁盘/屏幕/传感器/相机/agents 能力)
 * 与**运行时** (不进 Git: 在线/电量/忙闲) 不再混在一张卡里 —— 后者仍由心跳承担。
 */
data class DeviceInventory(
    val deviceId: String,
    /** 仅诊断/审计展示, **不用于信任迁移** (自报 id 可被冒用, 见 §7.1)。 */
    val legacyFingerprint: String = "",
    val deviceName: String = "",
    val formFactor: String = FORM_UNKNOWN,
    val os: String = "",
    val cpuCores: Int = 0,
    val ramMB: Long = 0L,
    val storageFreeMB: Long = 0L,
    /** `宽x高@dpi`, 例 `2560x1600@320`。 */
    val screen: String = "",
    val sensors: List<String> = emptyList(),
    val camera: List<String> = emptyList(),
    /** ISO-8601 带偏移, 例 `2026-10-22T22:41+08:00`; 时效计算用 (见 [DeviceInventoryStore.ageDays])。 */
    val observedAt: String = "",
    val schemaVersion: Int = SCHEMA_VERSION,
    val status: String = STATUS_ACTIVE,
    val agents: List<AgentEntry> = emptyList(),
    /** frontmatter 未知键原样保留 (对端可能更高版本或第三方工具写的)。 */
    val extra: Map<String, String> = emptyMap(),
    /** `---` 之后的人读正文 (`# 名称` + 备注) — 解析/序列化往返不丢, Agent 可改。 */
    val body: String = ""
) {

    val isRetired: Boolean get() = status.equals(STATUS_RETIRED, ignoreCase = true)

    /** 本行归属名 (空名回落 deviceId 前 8 位, 让索引/提示词永远有可读标识)。 */
    val displayName: String
        get() = deviceName.trim().ifBlank { deviceId.take(8).ifBlank { "(未命名设备)" } }

    /** 所有端登记的能力关键词 (插件/传感器/相机/形态/视觉), 供 `device.find` 与索引展示。 */
    fun capabilities(): List<String> {
        val out = LinkedHashSet<String>()
        agents.forEach { agent ->
            out.addAll(agent.plugins)
            if (agent.vision == true) out.add("vision")
            if (agent.tools == true) out.add("tools")
        }
        out.addAll(sensors)
        if (camera.isNotEmpty()) out.add("camera")
        if (formFactor.isNotBlank() && !formFactor.equals(FORM_UNKNOWN, ignoreCase = true)) {
            out.add(formFactor.lowercase())
        }
        return out.toList()
    }

    /** 单行摘要 (`名称（形态, N 核 / XG, 能力）`), 索引与提示词共用。 */
    fun hardwareLine(): String = buildString {
        append(displayName)
        append('（')
        append(formFactor.ifBlank { FORM_UNKNOWN })
        if (cpuCores > 0) append(", ").append(cpuCores).append(" 核")
        if (ramMB > 0) append(" / ").append(ramGBText())
        val caps = capabilities()
        if (caps.isNotEmpty()) append(", ").append(caps.take(MAX_CAPS_IN_LINE).joinToString("+"))
        append('）')
    }

    /** RAM 展示: ≥1024MB 折成 G, 否则 MB。 */
    fun ramGBText(): String = if (ramMB >= 1024) "${ramMB / 1024}G" else "${ramMB}M"

    /** 是否与本行在 frontmatter 层完全一致 (排除正文) — `device.refresh` 的"有差异才写"。 */
    fun sameFrontmatter(other: DeviceInventory): Boolean = copy(body = "") == other.copy(body = "")

    companion object {
        const val SCHEMA_VERSION = 1
        const val STATUS_ACTIVE = "active"
        const val STATUS_RETIRED = "retired"
        const val FORM_UNKNOWN = "UNKNOWN"

        /** 索引/提示词单行最多列出的能力数。 */
        const val MAX_CAPS_IN_LINE = 4

        /** 解析失败 (坏 frontmatter) → null, 绝不抛 (库存缺失只降级, 不阻断同步/命令)。 */
        fun parse(text: String): DeviceInventory? = DeviceInventoryFormat.parse(text)

        /** 渲染 md (frontmatter + 人读正文)。键序固定 → 同内容同文本, 便于"有差异才写"。 */
        fun serialize(inv: DeviceInventory): String = DeviceInventoryFormat.serialize(inv)
    }
}

/**
 * 单端 (agent) 能力登记 — `agents[]` 的一项。
 *
 * `sources` 记录每个判定的来源 (`model: measured` / `vision: declared`), 让路由知道"凭什么"。
 */
data class AgentEntry(
    val name: String,
    val model: String = "",
    val contextTokens: Int = 0,
    /** null = 未知 (中性处理, 不当作"不支持"扣分)。 */
    val vision: Boolean? = null,
    val tools: Boolean? = null,
    val plugins: List<String> = emptyList(),
    val observedAt: String = "",
    val sources: Map<String, String> = emptyMap(),
    val extra: Map<String, String> = emptyMap()
)
