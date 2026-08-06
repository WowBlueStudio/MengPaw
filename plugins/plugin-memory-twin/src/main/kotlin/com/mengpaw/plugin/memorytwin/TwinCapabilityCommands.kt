// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * 孪生能力命令组 — 从 MemoryTwinPlugin 拆分。
 * capabilities / delegate / route (能力卡采集与任务路由)。
 *
 * 状态读写走 [TwinRuntimeState]; 全局依赖 (appContext/llmProvider/pluginNames/
 * agentSessionId/agentIsBusy) 读 [MemoryTwinPlugin] companion。
 */
internal class TwinCapabilityCommands(
    private val state: TwinRuntimeState
) {

    // ── Capability commands ───────────────────────────────────────

    suspend fun cmdCapabilities(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val context = MemoryTwinPlugin.appContext ?: return ExecutionResult.fail("无法获取设备上下文")
        val flag = args.getOrNull(0) ?: "--self"

        return when (flag) {
            "--self" -> {
                val collector = TwinCapabilityCollector(context, state.deviceId, state.deviceName,
                    mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION)
                val card = collector.collect(MemoryTwinPlugin.llmProvider, MemoryTwinPlugin.pluginNames,
                    currentSessionId = MemoryTwinPlugin.agentSessionId,
                    isBusy = MemoryTwinPlugin.agentIsBusy)
                ExecutionResult.ok(card.toJson())
            }
            "--all" -> {
                // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
                if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
                val collector = TwinCapabilityCollector(context, state.deviceId, state.deviceName,
                    mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION)
                val selfCard = collector.collect(MemoryTwinPlugin.llmProvider, MemoryTwinPlugin.pluginNames,
                    currentSessionId = MemoryTwinPlugin.agentSessionId,
                    isBusy = MemoryTwinPlugin.agentIsBusy)
                val peers = state.syncEngine.getPeers()
                val peerCards = peers.mapNotNull { peer ->
                    peer.capabilityCard?.let { CapabilityCard.fromJson(it) }
                }
                val sb = StringBuilder()
                sb.appendLine("## 设备能力对比")
                sb.appendLine()
                sb.appendLine("| 设备 | 形态 | 模型 | 上下文 | 摄像头 | 电池 |")
                sb.appendLine("|------|------|------|--------|--------|------|")
                sb.appendLine(capabilityRow(selfCard))
                peerCards.forEach { sb.appendLine(capabilityRow(it)) }
                sb.appendLine()
                sb.appendLine("> 使用 twin.route <任务> 获取任务路由推荐")
                ExecutionResult.ok(sb.toString())
            }
            else -> {
                // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
                if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
                val peers = state.syncEngine.getPeers()
                val peer = peers.find { it.peerId == flag || it.peerId.startsWith(flag) }
                val card = peer?.capabilityCard
                if (card != null) {
                    ExecutionResult.ok(card)
                } else {
                    ExecutionResult.fail("未找到该节点的能力卡: $flag。使用 twin.peers 查看所有节点。")
                }
            }
        }
    }

    private fun capabilityRow(card: CapabilityCard): String {
        val camera = if (card.hardware.hasCamera) "✓ ${card.hardware.cameraFacing.joinToString()}" else "✗"
        val battery = "${card.hardware.batteryLevel}%${if (card.hardware.isCharging) " ⚡" else ""}"
        return "| ${card.deviceName} | ${card.formFactor.name} | ${card.model.modelName} | ${card.model.contextWindowTokens / 1000}K | $camera | $battery |"
    }

    suspend fun cmdDelegate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: twin.delegate <peer-id> <task>")
        // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
        val peerId = args[0]
        val task = args.drop(1).joinToString(" ")
        val peers = state.syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId。使用 twin.peers 查看所有已知节点。")

        if (!com.mengpaw.kernel.security.PromptFirewall.isTrusted(peerId)) {
            return ExecutionResult.fail("未配对设备: $peerId。请先完成孪生配对（侧边栏 5 连击 MengPaw 框架图标）。")
        }

        val msg = com.mengpaw.kernel.acp.AcpMessage.twinDelegate(state.deviceId, peerId, task)
        val sent = MemoryTwinPlugin.acpTransport?.send(msg) ?: false
        return if (sent) {
            ExecutionResult.ok("任务已委派到 ${peer.agentName} ($peerId) — 使用 twin.status 查看状态")
        } else {
            ExecutionResult.fail("发送失败: 对端 ${peer.address}:${peer.port} 不可达。\n检查: 1) 对端是否在线 2) 网络是否互通 3) 防火墙是否拦截端口 ${com.mengpaw.kernel.ports.Ports.ACP}")
        }
    }

    suspend fun cmdRoute(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val task = args.joinToString(" ")
        if (task.isBlank()) return ExecutionResult.fail("用法: twin.route <任务描述>")
        // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")

        val context = MemoryTwinPlugin.appContext ?: return ExecutionResult.fail("无法获取设备上下文")
        val collector = TwinCapabilityCollector(context, state.deviceId, state.deviceName,
            mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION)
        val selfCard = collector.collect(MemoryTwinPlugin.llmProvider, MemoryTwinPlugin.pluginNames,
            currentSessionId = MemoryTwinPlugin.agentSessionId,
            isBusy = MemoryTwinPlugin.agentIsBusy)
        val peers = state.syncEngine.getPeers()
        val peerCards = peers.mapNotNull { peer ->
            peer.capabilityCard?.let { CapabilityCard.fromJson(it) }
        }

        val analysis = TwinRouter.route(task, selfCard, peerCards)
        return ExecutionResult.ok(analysis.summary)
    }
}
