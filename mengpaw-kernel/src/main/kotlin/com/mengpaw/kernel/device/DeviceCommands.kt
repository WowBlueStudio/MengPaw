// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/** `device.refresh` / 启动采集的结果 — 便于插件与命令共用同一判定。 */
data class InventoryRefresh(
    val write: InventoryWrite,
    val inventory: DeviceInventory?,
    val message: String
)

/**
 * `device.*` 命名空间 (P4, docs/memory-git-sync-design.md §9.1) — 设备/能力库存。
 *
 * ```
 * device.self             — 本机库存一行 + 寄存声明
 * device.list             — 全部设备 (名称/形态/模型/时效/状态)
 * device.info <name>      — 单端完整库存
 * device.find <capability>— 按能力找端 (browser / vision / camera / tablet ...)
 * device.refresh          — 重测本机并提交库存 (有差异才写)
 * ```
 *
 * 只写本机行 ([DeviceInventoryStore.write] 单写者校验); 对端的行由 `dev` 命名空间同步落盘。
 */
object DeviceCommands {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "self" to ::self,
        "list" to ::list,
        "info" to ::info,
        "find" to ::find,
        "refresh" to ::refresh
    )

    /** 能力查询别名 (中英/口语 → 库存关键词)。 */
    private val CAP_ALIASES = mapOf(
        "浏览器" to listOf("browser"), "web" to listOf("browser"), "网页" to listOf("browser"),
        "视觉" to listOf("vision"), "看图" to listOf("vision"), "图片" to listOf("vision"),
        "相机" to listOf("camera"), "摄像头" to listOf("camera"), "拍照" to listOf("camera"),
        "定位" to listOf("gps", "location"), "导航" to listOf("gps", "location"),
        "大屏" to listOf("tablet", "tv"), "平板" to listOf("tablet")
    )

    // ── device.self ────────────────────────────────────────────────

    private suspend fun self(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val localId = DeviceInventoryStore.localDeviceId()
        val inv = DeviceInventoryStore.readLocal()
            ?: return ExecutionResult.ok(
                "本机尚未登记库存 — 执行 `device.refresh` 采集本机硬件与能力。\n" +
                    "- 预期文件: ${DataPaths.TWIN_DEVICES}/<本机 deviceId>.md"
            )
        return ExecutionResult.ok(buildString {
            appendLine("## 本机库存")
            appendLine("- 设备: ${DeviceIndexWriter.row(inv, localId)}")
            appendLine("- deviceId: $localId")
            if (inv.legacyFingerprint.isNotBlank()) {
                appendLine("- legacyFingerprint: ${inv.legacyFingerprint} (仅诊断, 不用于信任迁移)")
            }
            appendLine("- OS: ${inv.os.ifBlank { "未采集" }}")
            if (inv.screen.isNotBlank()) appendLine("- 屏幕: ${inv.screen}")
            if (inv.storageFreeMB > 0) appendLine("- 剩余存储: ${inv.storageFreeMB / 1024}G")
            val caps = inv.capabilities()
            appendLine("- 能力: ${if (caps.isEmpty()) "未登记" else caps.joinToString(", ")}")
            inv.agents.forEach { a ->
                appendLine("- Agent ${a.name}: ${agentLine(a)}")
            }
            // 运行时字段 (§7.3): 只展示, 不入库存 —— 避免每次刷新都产生差异
            DeviceCollectors.snapshot()?.let { live ->
                if (live.batteryLevel >= 0) {
                    appendLine("- 实时电量: ${live.batteryLevel}%${if (live.isCharging) " ⚡充电中" else ""} (运行时, 不入库存)")
                }
            }
            appendLine("- 文件: ${DataPaths.TWIN_DEVICES}/${inv.deviceId}.md")
            appendLine()
            appendLine("> 寄存声明: 你只登记自己那一行 — 其他端的环境由它们自己登记, 不要替它们补写。")
        })
    }

    // ── device.list ────────────────────────────────────────────────

    private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val localId = DeviceInventoryStore.localDeviceId()
        val all = DeviceInventoryStore.readAll()
        if (all.isEmpty()) {
            return ExecutionResult.ok(
                "设备库存为空 — 执行 `device.refresh` 登记本机; 对端库存随孪生 dev 命名空间同步到达。"
            )
        }
        val now = System.currentTimeMillis()
        return ExecutionResult.ok(buildString {
            appendLine("## 设备库存 (${all.size} 端)")
            all.sortedWith(compareBy({ if (it.deviceId == localId) 0 else 1 }, { it.displayName }))
                .forEach { appendLine(DeviceIndexWriter.row(it, localId, now)) }
            appendLine()
            appendLine("> device.info <名称> 看完整库存 · device.find <能力> 按能力找端")
        })
    }

    // ── device.info ────────────────────────────────────────────────

    private suspend fun info(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val name = args.firstOrNull()?.trim()
        if (name.isNullOrEmpty()) {
            return ExecutionResult.fail("用法: device.info <名称> — 名称可用 device.list 查看", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val all = DeviceInventoryStore.readAll()
        val hits = all.filter { it.displayName.equals(name, ignoreCase = true) }
            .ifEmpty { all.filter { it.displayName.contains(name, ignoreCase = true) || it.deviceId.startsWith(name) } }
        if (hits.isEmpty()) {
            return ExecutionResult.fail("未找到设备: $name — 用 device.list 查看全部库存")
        }
        if (hits.size > 1) {
            return ExecutionResult.ok(buildString {
                appendLine("匹配到 ${hits.size} 端 — 请用更精确的名称:")
                hits.forEach { appendLine("- ${it.displayName} (${it.deviceId.take(12)}…)") }
            })
        }
        val inv = hits.first()
        val now = System.currentTimeMillis()
        return ExecutionResult.ok(buildString {
            appendLine("## ${inv.displayName}")
            appendLine("- deviceId: ${inv.deviceId}")
            if (inv.legacyFingerprint.isNotBlank()) appendLine("- legacyFingerprint: ${inv.legacyFingerprint} (仅诊断)")
            appendLine("- 形态: ${inv.formFactor} · OS: ${inv.os.ifBlank { "未采集" }}")
            appendLine("- 硬件: ${inv.cpuCores} 核 / ${inv.ramGBText()} / 剩余 ${inv.storageFreeMB}MB")
            if (inv.screen.isNotBlank()) appendLine("- 屏幕: ${inv.screen}")
            if (inv.sensors.isNotEmpty()) appendLine("- 传感器: ${inv.sensors.joinToString(", ")}")
            if (inv.camera.isNotEmpty()) appendLine("- 相机: ${inv.camera.joinToString(", ")}")
            appendLine("- 观测: ${DeviceInventoryStore.ageText(inv, now)} (${inv.observedAt.ifBlank { "无时间记录" }})" +
                if (DeviceInventoryStore.isStale(inv, now)) " · ⚠️ 可能过时" else "")
            appendLine("- 状态: ${inv.status}")
            if (inv.agents.isEmpty()) {
                appendLine("- Agent: 未登记")
            } else {
                inv.agents.forEach { a -> appendLine("- Agent ${a.name}: ${agentLine(a)}") }
            }
            appendLine("- 文件: ${DataPaths.TWIN_DEVICES}/${inv.deviceId}.md")
        })
    }

    // ── device.find ────────────────────────────────────────────────

    private suspend fun find(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val raw = args.joinToString(" ").trim()
        if (raw.isEmpty()) {
            return ExecutionResult.fail(
                "用法: device.find <能力> — 如 browser / vision / camera / gps / tablet",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val all = DeviceInventoryStore.readAll()
        val hits = all.filter { matches(it, raw) }
        if (hits.isEmpty()) {
            return ExecutionResult.fail(
                "未找到具备「$raw」能力的设备 — 用 device.list 查看已登记能力; " +
                    "若该端尚未上报, 请在其上执行 device.refresh。"
            )
        }
        val now = System.currentTimeMillis()
        return ExecutionResult.ok(buildString {
            appendLine("## 具备「$raw」能力的设备 (${hits.size} 端)")
            hits.sortedBy { it.displayName }.forEach { appendLine(DeviceIndexWriter.row(it, now = now)) }
        })
    }

    // ── device.refresh ─────────────────────────────────────────────

    private suspend fun refresh(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val result = refreshLocal()
        return when (result.write) {
            InventoryWrite.WRITTEN, InventoryWrite.UNCHANGED -> ExecutionResult.ok(result.message)
            InventoryWrite.REJECTED_FOREIGN -> ExecutionResult.fail(result.message)
            InventoryWrite.FAILED -> ExecutionResult.fail(result.message)
        }
    }

    /**
     * 重测本机并写库存 (无差异不写) — 命令与 `twin.start` 启动采集共用。
     *
     * "有差异"只看**库存字段**: `observedAt` 每次都会变, 若把它计入差异则每次都写历史,
     * 与 §7.4"有差异才提交"相悖 —— 因此比较时对齐旧观测时间。
     */
    @Synchronized
    fun refreshLocal(): InventoryRefresh {
        val localId = DeviceInventoryStore.localDeviceId()
        val snapshot = DeviceCollectors.snapshot()
            ?: return InventoryRefresh(
                InventoryWrite.FAILED, null,
                "本机采集器未注册 — 无法刷新库存 (Android 端由 MainActivity 注册 DeviceCollectorImpl)"
            )
        val existing = DeviceInventoryStore.readLocal()
        val composed = snapshot.toInventory(
            deviceId = localId,
            legacyFingerprint = localId,
            agentFacts = DeviceInventoryStore.mergeAgents(existing?.agents.orEmpty(), DeviceCollectors.agentFacts()),
            observedAt = nowIso()
        )
        if (existing != null && existing.withoutTimestamps() == composed.withoutTimestamps()) {
            return InventoryRefresh(
                InventoryWrite.UNCHANGED, existing,
                "无变化 — 本机库存已是最新 (观测时间保持 ${existing.observedAt.ifBlank { "无" }})"
            )
        }
        val outcome = DeviceInventoryStore.write(composed, localId)
        val text = when (outcome) {
            InventoryWrite.WRITTEN ->
                "已更新本机库存: ${composed.hardwareLine()}\n" +
                    "- 文件: ${DataPaths.TWIN_DEVICES}/$localId.md\n" +
                    "- 观测: ${composed.observedAt} · 将随 v0.3 同步 (dev 命名空间) 到达其他端"
            InventoryWrite.UNCHANGED -> "无变化 — 本机库存已是最新"
            InventoryWrite.REJECTED_FOREIGN -> "拒绝写入: 只能登记本机那一行 ($localId)"
            InventoryWrite.FAILED -> "写入失败 — 检查 ${DataPaths.TWIN_DEVICES} 权限"
        }
        return InventoryRefresh(outcome, composed, text)
    }

    // ── 内部 ───────────────────────────────────────────────────────

    /** 去掉时间戳后的库存视图 — "有差异才写"只比库存字段 (§7.4), 时间新鲜度不算差异。 */
    private fun DeviceInventory.withoutTimestamps(): DeviceInventory =
        copy(observedAt = "", agents = agents.map { it.copy(observedAt = "") })

    private fun matches(inv: DeviceInventory, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        val keys = (listOf(q) + CAP_ALIASES[q].orEmpty()).toSet()
        return inv.capabilities().any { cap ->
            val c = cap.lowercase()
            keys.any { k -> c == k || c.contains(k) }
        } || inv.agents.any { it.model.lowercase().contains(q) }
    }

    private fun agentLine(a: AgentEntry): String = buildString {
        append(a.model.ifBlank { "模型未登记" })
        if (a.contextTokens > 0) append(" / ${a.contextTokens / 1000}K")
        a.vision?.let { append(if (it) " / 视觉✓" else " / 无视觉") }
        if (a.plugins.isNotEmpty()) append(" / ${a.plugins.joinToString("+")}")
        if (a.sources.isNotEmpty()) {
            append("（来源: ").append(a.sources.entries.joinToString(", ") { "${it.key}=${it.value}" }).append("）")
        }
    }
}
