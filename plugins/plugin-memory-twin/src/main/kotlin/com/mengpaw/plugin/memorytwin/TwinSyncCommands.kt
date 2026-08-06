// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * 孪生同步命令组 — 从 MemoryTwinPlugin 拆分 (sync / sync.auto / sync.qos)。
 *
 * 状态读写走 [TwinRuntimeState]; 命令注册名与返回语义与拆分前完全一致。
 */
internal class TwinSyncCommands(
    private val state: TwinRuntimeState
) {

    // ── Sync commands ─────────────────────────────────────────────

    suspend fun cmdSync(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")

        val peerId = args.getOrNull(0)
        val result: TwinSyncResult = if (peerId != null) {
            state.syncEngine.syncWithPeer(peerId)
        } else {
            val results = state.syncEngine.syncWithAllPeers()
            if (results.isEmpty()) TwinSyncResult(0, 0, 0, "无在线节点可同步", "使用 twin.peers 查看节点列表，确保对端在线")
            else if (results.all { it.filesReceived == 0 && it.error != null }) results.first()
            else TwinSyncResult(
                results.sumOf { it.filesReceived },
                results.sumOf { it.filesSent },
                results.sumOf { it.conflicts },
                null, null
            )
        }

        if (result.error != null) {
            return ExecutionResult.fail(buildString {
                appendLine("同步失败: ${result.error}")
                if (result.suggestion != null) appendLine("建议: ${result.suggestion}")
            })
        }
        return ExecutionResult.ok(buildString {
            appendLine("同步完成")
            if (result.filesReceived > 0 || result.conflicts > 0) {
                appendLine("- 接收文件: ${result.filesReceived}")
                appendLine("- 发送文件: ${result.filesSent}")
                appendLine("- 冲突 (已存 .conflict 备份): ${result.conflicts}")
                appendLine("- 使用 twin.status 查看详情")
            } else {
                appendLine("- 无差异文件 (工作区已一致)")
            }
        })
    }

    suspend fun cmdSyncAuto(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动")

        val mode = args.getOrNull(0)
            ?: return ExecutionResult.ok("自动同步: ${if (autoSyncActive()) "开启" else "关闭"} (${state.syncEngine.qosLevel.name})")

        return when (mode.lowercase()) {
            "on", "true", "enable" -> {
                state.syncEngine.startAutoSync()
                ExecutionResult.ok("自动同步已开启 (${state.syncEngine.qosLevel.name} 模式, 每 ${syncIntervalDisplay()} 秒)")
            }
            "off", "false", "disable" -> {
                state.syncEngine.stopAutoSync()
                ExecutionResult.ok("自动同步已关闭 — 使用 twin.sync 手动触发")
            }
            else -> ExecutionResult.fail("用法: twin.sync.auto [on|off]")
        }
    }

    private fun autoSyncActive(): Boolean {
        return try { state.syncEngine.syncState.value.phase != SyncPhase.IDLE || true } catch (_: Exception) { false }
    }

    private fun syncIntervalDisplay(): Long = when (state.syncEngine.qosLevel) {
        TwinSyncEngine.QosLevel.WIFI -> 60
        TwinSyncEngine.QosLevel.MOBILE -> 300
        TwinSyncEngine.QosLevel.METERED -> 0
    }

    suspend fun cmdSyncQos(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
        val mode = args.getOrNull(0)
        return when (mode?.lowercase()) {
            "wifi" -> {
                state.syncEngine.qosLevel = TwinSyncEngine.QosLevel.WIFI
                ExecutionResult.ok("QoS: WiFi — 全量同步 (每 60 秒)\n内容: 账本 + 身份 + 梦境")
            }
            "mobile" -> {
                state.syncEngine.qosLevel = TwinSyncEngine.QosLevel.MOBILE
                ExecutionResult.ok("QoS: 移动网络 — 仅关键记忆 (每 5 分钟)\n数据量更小，不传梦境和身份文档")
            }
            "metered" -> {
                state.syncEngine.qosLevel = TwinSyncEngine.QosLevel.METERED
                state.syncEngine.stopAutoSync()
                ExecutionResult.ok("QoS: 按流量计费 — 自动同步已暂停\n使用 twin.sync 手动触发同步")
            }
            else -> ExecutionResult.ok(buildString {
                appendLine("QoS 策略: ${state.syncEngine.qosLevel.name}")
                appendLine()
                appendLine("可选: wifi | mobile | metered")
                appendLine("- wifi: 全量同步, 每 60 秒 (默认)")
                appendLine("- mobile: 仅关键记忆, 每 5 分钟")
                appendLine("- metered: 暂停自动同步, 手动触发")
            })
        }
    }
}
