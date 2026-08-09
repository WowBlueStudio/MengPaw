// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import android.content.Context
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.plugin.*
import kotlinx.coroutines.delay

/**
 * Memory Twin Plugin — cross-device Agent workspace synchronization.
 *
 * Implements the [Plugin] interface to contribute `twin.*` CLI commands
 * to the MengPaw framework. v0.22.0: 哈希链账本 → 工作区文件同步 —
 * 同步整个 {agent}/ 工作区文档 (soul.md → memory/), manifest 比对 +
 * 差异传输 + LWW 冲突备份。
 *
 * ## Architecture
 * - [TwinWorkspace] — 同步范围/清单/冲突落盘 (替代账本)
 * - [TwinSyncEngine] — sync state machine (WS_MANIFEST→WS_PULL) + heartbeat + QoS
 * - [TwinAcpHandler] — ACP message handler (first AcpHandler implementation)
 * - [TwinDiscovery] — Android NSD LAN peer discovery
 * - [TwinPairingEngine] — short-code verification pairing protocol
 * - [TwinCapabilityCollector] — device capability card generation
 * - [TwinRouter] — capability-aware task routing
 *
 * ## 职责拆分 (批次3)
 * 命令实现按组拆到同包委托对象 (共享 [TwinRuntimeState]), 主类保留
 * companion 注入点 / 生命周期回调 / 命令注册, 公开 API 零变化:
 * - [TwinLifecycleCommands] — start/stop/status
 * - [TwinPeerCommands] — peers/peer.info/peer.add/pair/unpair/lost/recover
 * - [TwinSyncCommands] — sync/sync.auto/sync.qos
 * - [TwinCapabilityCommands] — capabilities/delegate/route
 */
class MemoryTwinPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "memory-twin-plugin",
        name = "记忆孪生",
        version = "", // 内置插件, 随 Shell APK 版本更新
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "跨设备工作区同步 — ACP P2P 文件同步 + 能力感知路由 + 心跳保活",
        minCoreVersion = "0.12.0",
        commands = listOf(
            "twin.start", "twin.stop", "twin.status",
            "twin.peers", "twin.peer.info", "twin.peer.add",
            "twin.pair", "twin.unpair",
            "twin.sync", "twin.sync.auto", "twin.sync.qos",
            "twin.capabilities",
            "twin.delegate", "twin.route",
            "twin.lost", "twin.recover"
        )
    )

    // ── Dependencies (injected via companion) ────────────────────

    companion object {
        @Volatile var appContext: Context? = null
        @Volatile var llmProvider: LlmProvider? = null
        @Volatile var pluginNames: List<String> = emptyList()
        @Volatile var agentName: String = "MengPaw"
        @Volatile var acpServer: AcpServer? = null
        @Volatile var acpTransport: AcpTransport? = null
        /** 当前注册在共享 AcpHolder.server 上的孪生 handler (v0.35.4 共享 server 后防重复注册)。 */
        @Volatile var twinAcpHandler: TwinAcpHandler? = null
        @Volatile var twinProfile: com.mengpaw.kernel.agent.AgentProfile? = null
        @Volatile var agentEngine: com.mengpaw.kernel.AgentEngine? = null
        /** MainActivity 激活时创建的 engine — cmdStart 复用, 避免双引擎 (v0.22.0 债务修复)。 */
        @Volatile var activeEngine: TwinSyncEngine? = null
        val isActivated: Boolean get() = acpServer != null

        /** Read active session ID from AgentEngine if available. */
        val agentSessionId: String? get() = agentEngine?.activeSessionId
        /** Read execution state from AgentEngine if available. */
        val agentIsBusy: Boolean get() = agentEngine?.isExecuting ?: false

        val pendingPairRequests = kotlinx.coroutines.flow.MutableStateFlow<List<TwinPairRequest>>(emptyList())

        fun acceptPairRequest(requestId: String) {
            val request = pendingPairRequests.value.find { it.id == requestId } ?: return
            pendingPairRequests.value = pendingPairRequests.value.filter { it.id != requestId }
            val existingSession = TwinPairingEngine.getSessionForPeer(request.deviceId)
            if (existingSession == null) {
                com.mengpaw.kernel.security.PromptFirewall.trust(request.deviceId, request.deviceName)
            }
        }

        fun rejectPairRequest(requestId: String) {
            val request = pendingPairRequests.value.find { it.id == requestId }
            pendingPairRequests.value = pendingPairRequests.value.filter { it.id != requestId }
            if (request != null) {
                TwinPairingEngine.rejectPairing(request.deviceId)
            }
        }

        /**
         * Poll until ACP transport is ready (listening on port).
         * Returns true when ready, false on timeout.
         */
        suspend fun awaitAcpReady(timeoutMs: Long = 5000L): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                val t = acpTransport
                if (t != null && t.isConnected()) return true
                delay(200L)
            }
            return acpTransport?.let { it.isConnected() } == true
        }
    }

    data class TwinPairRequest(
        val id: String,
        val deviceId: String,
        val deviceName: String,
        val peerAddress: String,
        val capabilityCard: CapabilityCard?,
        val receivedAt: Long = System.currentTimeMillis()
    )

    // ── Internal state (共享给命令组, 批次3 拆分) ───────────────────

    private val state = TwinRuntimeState()

    private val lifecycleCommands = TwinLifecycleCommands(state)
    private val peerCommands = TwinPeerCommands(state)
    private val syncCommands = TwinSyncCommands(state)
    private val capabilityCommands = TwinCapabilityCommands(state)

    // ── Lifecycle ─────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        ctx.log("记忆孪生插件已安装 — 通过侧边栏 5 连击 MengPaw 框架图标激活")
    }

    override suspend fun onUninstall() {
        lifecycleCommands.stopTwinService()
    }

    // ── CLI Commands ──────────────────────────────────────────────

    override val commands: Map<String, CommandHandler> = mapOf(
        "start" to lifecycleCommands::cmdStart,
        "stop" to lifecycleCommands::cmdStop,
        "status" to lifecycleCommands::cmdStatus,
        "peers" to peerCommands::cmdPeers,
        "peer.info" to peerCommands::cmdPeerInfo,
        "peer.add" to peerCommands::cmdPeerAdd,
        "pair" to peerCommands::cmdPair,
        "unpair" to peerCommands::cmdUnpair,
        "sync" to syncCommands::cmdSync,
        "sync.auto" to syncCommands::cmdSyncAuto,
        "sync.qos" to syncCommands::cmdSyncQos,
        "capabilities" to capabilityCommands::cmdCapabilities,
        "delegate" to capabilityCommands::cmdDelegate,
        "route" to capabilityCommands::cmdRoute,
        "lost" to peerCommands::cmdLost,
        "recover" to peerCommands::cmdRecover
    )
}
