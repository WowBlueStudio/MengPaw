// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import android.content.Context
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpCrypto
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.plugin.*
import kotlinx.coroutines.*

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

    // ── Internal state ────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val deviceId: String by lazy {
        try { AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
    }
    private val deviceName: String by lazy {
        try { android.os.Build.MODEL ?: "Android Device" } catch (_: Exception) { "Android Device" }
    }

    private lateinit var syncEngine: TwinSyncEngine
    private lateinit var acpHandler: TwinAcpHandler
    private var discovery: TwinDiscovery? = null
    private var isRunning = false
    /** P1.4: Auto-collect broadcast receiver (registered in cmdStart, unregistered in stopTwinService). */
    private var autoCollectReceiver: android.content.BroadcastReceiver? = null

    // ── Lifecycle ─────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        ctx.log("记忆孪生插件已安装 — 通过侧边栏 5 连击 MengPaw 框架图标激活")
    }

    override suspend fun onUninstall() {
        stopTwinService()
    }

    // ── CLI Commands ──────────────────────────────────────────────

    override val commands: Map<String, CommandHandler> = mapOf(
        "start" to ::cmdStart,
        "stop" to ::cmdStop,
        "status" to ::cmdStatus,
        "peers" to ::cmdPeers,
        "peer.info" to ::cmdPeerInfo,
        "peer.add" to ::cmdPeerAdd,
        "pair" to ::cmdPair,
        "unpair" to ::cmdUnpair,
        "sync" to ::cmdSync,
        "sync.auto" to ::cmdSyncAuto,
        "sync.qos" to ::cmdSyncQos,
        "capabilities" to ::cmdCapabilities,
        "delegate" to ::cmdDelegate,
        "route" to ::cmdRoute,
        "lost" to ::cmdLost,
        "recover" to ::cmdRecover
    )

    // ── Twin lifecycle commands ───────────────────────────────────

    private suspend fun cmdStart(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (isRunning) return ExecutionResult.ok("孪生服务已在运行中")

        val server = acpServer ?: return ExecutionResult.fail(
            "ACP 服务未启动。请先执行 self.acp start，然后重试 twin.start"
        )
        val transport = acpTransport ?: return ExecutionResult.fail(
            "ACP 传输层未初始化。请检查 ACP 服务状态: self.acp status"
        )

        // 复用 MainActivity 激活时创建的 engine (双引擎债务修复, v0.22.0)
        val reused = activeEngine
        if (reused != null && reused != syncEngine) {
            syncEngine = reused
            acpHandler = TwinAcpHandler(syncEngine)
        }
        if (!::syncEngine.isInitialized) {
            syncEngine = TwinSyncEngine(
                serverSupplier = { acpServer }, transportSupplier = { acpTransport },
                agentName = agentName, deviceId = deviceId, deviceName = deviceName
            )
            acpHandler = TwinAcpHandler(syncEngine)
            server.registerHandler(acpHandler)
        }

        val context = appContext
        if (context != null && discovery == null) {
            discovery = TwinDiscovery(context, deviceId, agentName)
            discovery?.start()

            // P1.4: Register auto-collect broadcast receivers
            autoCollectReceiver = TwinCapabilityCollector.registerAutoCollect(context) { card ->
                scope.launch {
                    android.util.Log.i("MengPawTwin", "系统状态变化,自动更新能力卡")
                    syncEngine.broadcastCapability(card.toJson())
                }
            }
        }

        isRunning = true
        if (reused == null) syncEngine.startAutoSync()

        return ExecutionResult.ok(buildString {
            appendLine("孪生服务已启动")
            appendLine("- 设备: $deviceName (${deviceId.take(12)}...)")
            appendLine("- 自动同步: 每 60 秒 (WiFi)")
            appendLine("- 心跳保活: 每 30 秒")
            appendLine()
            appendLine("下一步:")
            appendLine("- twin.peers — 查看已发现节点")
            appendLine("- 通过侧边栏 MengPaw 框架图标 5 连击发起配对")
        })
    }

    private suspend fun cmdStop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return if (stopTwinService()) {
            ExecutionResult.ok("孪生服务已停止 — 使用 twin.start 重新启动")
        } else {
            ExecutionResult.ok("孪生服务未在运行")
        }
    }

    private fun stopTwinService(): Boolean {
        if (!isRunning) return false
        syncEngine.stopAutoSync()
        discovery?.stop()
        // P1.4: Unregister auto-collect
        autoCollectReceiver?.let {
            try { appContext?.unregisterReceiver(it) } catch (_: Exception) {}
            autoCollectReceiver = null
        }
        isRunning = false
        return true
    }

    private suspend fun cmdStatus(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.ok("孪生服务: 未启动。使用 twin.start 启动。")

        val state = syncEngine.syncState.value
        return ExecutionResult.ok(buildString {
            appendLine("## 孪生状态")
            appendLine("- 服务: 运行中")
            appendLine("- 设备: $deviceName")
            appendLine("- 指纹: ${deviceId.take(16)}...")
            appendLine("- 协议版本: 0.2 (工作区文件同步)")
            appendLine("- 同步阶段: ${state.phase}")
            appendLine("- 在线节点: ${state.onlinePeers}/${state.totalPeers}")
            appendLine("- QoS: ${syncEngine.qosLevel.name}")
            appendLine("- 上次同步: ${
                if (state.lastSyncAt > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(state.lastSyncAt)) else "从未"
            }")
            if (state.lastFilesReceived > 0 || state.lastConflicts > 0) {
                appendLine("- 上次接收: ${state.lastFilesReceived} 个文件, 冲突 ${state.lastConflicts}")
            }
        })
    }

    // ── Peer management ───────────────────────────────────────────

    private suspend fun cmdPeers(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peers = syncEngine.getPeers()
        if (peers.isEmpty()) return ExecutionResult.ok(buildString {
            appendLine("(无已知孪生节点)")
            appendLine()
            appendLine("可能原因:")
            appendLine("1. 对端设备未在同一 WiFi 网络")
            appendLine("2. 对端设备未激活孪生服务")
            appendLine("3. mDNS 发现失败 — 可尝试 twin.peer.add <ip> 手动添加")
        })
        return ExecutionResult.ok(buildString {
            appendLine("| 设备 | Agent | 地址 | 在线 | 同步 |")
            appendLine("|------|-------|------|:--:|------|")
            peers.forEach { p ->
                val lastSeen = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(p.lastSeen))
                val onlineIcon = if (p.online) "🟢" else "⚫"
                val syncState = if (p.lastSyncAt > 0) "已同步" else "待同步"
                appendLine("| ${p.peerId.take(8)}... | ${p.agentName} | ${p.address}:${p.port} | $onlineIcon | $syncState |")
            }
        })
    }

    private suspend fun cmdPeerInfo(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0) ?: return ExecutionResult.fail("用法: twin.peer.info <peer-id>")
        val peers = syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId。使用 twin.peers 查看所有已知节点。")
        return ExecutionResult.ok(buildString {
            appendLine("## 节点信息")
            appendLine("- ID: ${peer.peerId}")
            appendLine("- Agent: ${peer.agentName}")
            appendLine("- 地址: ${peer.address}:${peer.port}")
            appendLine("- 在线: ${if (peer.online) "是" else "否"}")
            appendLine("- 最后在线: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(peer.lastSeen))}")
            if (peer.capabilityCard != null) {
                appendLine()
                appendLine("### 能力卡")
                appendLine(peer.capabilityCard)
            }
        })
    }

    private suspend fun cmdPeerAdd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
        val address = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.peer.add <ip> [port] [name]")
        val port = args.getOrNull(1)?.toIntOrNull() ?: com.mengpaw.kernel.ports.Ports.ACP
        val name = args.getOrNull(2)
        val peer = syncEngine.addManualPeer(address, port, name)
        return ExecutionResult.ok(buildString {
            appendLine("已手动添加节点:")
            appendLine("- ID: ${peer.peerId}")
            appendLine("- 地址: ${peer.address}:${peer.port}")
            appendLine()
            appendLine("使用 twin.sync ${peer.peerId} 发起同步")
        })
    }

    private suspend fun cmdPair(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "⚠️ 孪生配对不通过 CLI 执行。\n" +
            "在侧边栏找到 MengPaw 框架图标，连续点击 5 次即可发起配对。\n" +
            "双方会显示 6 位验证码，比对一致后配对完成。"
        )
    }

    private suspend fun cmdUnpair(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "⚠️ 解绑操作在侧边栏完成。\n" +
            "长按框架名片 → 点击「解除孪生」按钮即可。"
        )
    }

    // ── P1.2/P2.3: Device loss / revoke commands ─────────────────────

    private suspend fun cmdLost(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.lost <peer-id>\n\n标记设备丢失，广播解绑到所有在线节点。\n获取 peer-id: twin.peers")
        if (!isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")

        val peers = syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId。使用 twin.peers 查看所有已知节点。")

        // 1. Broadcast revoke to all online peers (except the lost device)
        scope.launch { syncEngine.broadcastRevoke(peer.peerId) }

        // 2. Remove local trust
        val trustedFile = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${peer.peerId}.trusted")
        if (trustedFile.exists()) trustedFile.delete()
        val keyFile = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${peer.peerId}.key")
        if (keyFile.exists()) keyFile.delete()

        // 3. Move peer to "lost" in display and remove from active peers
        syncEngine.onRevokeReceived(peer.peerId)

        // 4. Write audit record
        val auditMsg = buildString {
            appendLine("⚠️ 设备丢失标记")
            appendLine("- 设备: ${peer.agentName} (${peer.peerId})")
            appendLine("- 地址: ${peer.address}:${peer.port}")
            appendLine("- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine()
            appendLine("已执行:")
            appendLine("✓ 广播解绑到所有在线节点")
            appendLine("✓ 移除本地信任关系")
            appendLine()
            appendLine("如果找回了设备，使用以下命令重新配对:")
            appendLine("  twin.recover ${peer.peerId}")
            appendLine("  或在侧边栏 5 连击框架图标重新发起配对")
        }
        android.util.Log.w("MengPawTwin", auditMsg)
        return ExecutionResult.ok(auditMsg)
    }

    private suspend fun cmdRecover(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.recover <peer-id>\n\n找回设备后重新激活孪生。需要重新在两侧完成配对验证。")
        return ExecutionResult.ok(
            "设备找回流程:\n\n" +
            "1. 在找回的设备上: 确保已连接同一 WiFi\n" +
            "2. 在两侧执行 twin.start 启动孪生服务\n" +
            "3. 在任一侧侧边栏 5 连击框架图标发起配对\n" +
            "4. 验证配对码并确认\n" +
            "5. 配对完成后执行 twin.sync 全量同步恢复记忆\n\n" +
            "⚠️ 解绑期间的记忆不会自动恢复\n" +
            "⚠️ 如果设备未找回，可联系 wowblue 支持进行远程擦除"
        )
    }

    // ── Sync commands ─────────────────────────────────────────────

    private suspend fun cmdSync(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")

        val peerId = args.getOrNull(0)
        val result: TwinSyncResult = if (peerId != null) {
            syncEngine.syncWithPeer(peerId)
        } else {
            val results = syncEngine.syncWithAllPeers()
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

    private suspend fun cmdSyncAuto(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.fail("孪生服务未启动")

        val mode = args.getOrNull(0)
            ?: return ExecutionResult.ok("自动同步: ${if (autoSyncActive()) "开启" else "关闭"} (${syncEngine.qosLevel.name})")

        return when (mode.lowercase()) {
            "on", "true", "enable" -> {
                syncEngine.startAutoSync()
                ExecutionResult.ok("自动同步已开启 (${syncEngine.qosLevel.name} 模式, 每 ${syncIntervalDisplay()} 秒)")
            }
            "off", "false", "disable" -> {
                syncEngine.stopAutoSync()
                ExecutionResult.ok("自动同步已关闭 — 使用 twin.sync 手动触发")
            }
            else -> ExecutionResult.fail("用法: twin.sync.auto [on|off]")
        }
    }

    private fun autoSyncActive(): Boolean {
        return try { syncEngine.syncState.value.phase != SyncPhase.IDLE || true } catch (_: Exception) { false }
    }

    private fun syncIntervalDisplay(): Long = when (syncEngine.qosLevel) {
        TwinSyncEngine.QosLevel.WIFI -> 60
        TwinSyncEngine.QosLevel.MOBILE -> 300
        TwinSyncEngine.QosLevel.METERED -> 0
    }

    private suspend fun cmdSyncQos(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val mode = args.getOrNull(0)
        return when (mode?.lowercase()) {
            "wifi" -> {
                syncEngine.qosLevel = TwinSyncEngine.QosLevel.WIFI
                ExecutionResult.ok("QoS: WiFi — 全量同步 (每 60 秒)\n内容: 账本 + 身份 + 梦境")
            }
            "mobile" -> {
                syncEngine.qosLevel = TwinSyncEngine.QosLevel.MOBILE
                ExecutionResult.ok("QoS: 移动网络 — 仅关键记忆 (每 5 分钟)\n数据量更小，不传梦境和身份文档")
            }
            "metered" -> {
                syncEngine.qosLevel = TwinSyncEngine.QosLevel.METERED
                syncEngine.stopAutoSync()
                ExecutionResult.ok("QoS: 按流量计费 — 自动同步已暂停\n使用 twin.sync 手动触发同步")
            }
            else -> ExecutionResult.ok(buildString {
                appendLine("QoS 策略: ${syncEngine.qosLevel.name}")
                appendLine()
                appendLine("可选: wifi | mobile | metered")
                appendLine("- wifi: 全量同步, 每 60 秒 (默认)")
                appendLine("- mobile: 仅关键记忆, 每 5 分钟")
                appendLine("- metered: 暂停自动同步, 手动触发")
            })
        }
    }

    // ── Capability commands ───────────────────────────────────────

    private suspend fun cmdCapabilities(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val context = appContext ?: return ExecutionResult.fail("无法获取设备上下文")
        val flag = args.getOrNull(0) ?: "--self"

        return when (flag) {
            "--self" -> {
                val collector = TwinCapabilityCollector(context, deviceId, deviceName,
                    mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION)
                val card = collector.collect(llmProvider, pluginNames,
                    currentSessionId = agentSessionId,
                    isBusy = agentIsBusy)
                ExecutionResult.ok(card.toJson())
            }
            "--all" -> {
                val collector = TwinCapabilityCollector(context, deviceId, deviceName,
                    mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION)
                val selfCard = collector.collect(llmProvider, pluginNames,
                    currentSessionId = agentSessionId,
                    isBusy = agentIsBusy)
                val peers = syncEngine.getPeers()
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
                val peers = syncEngine.getPeers()
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

    private suspend fun cmdDelegate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: twin.delegate <peer-id> <task>")
        val peerId = args[0]
        val task = args.drop(1).joinToString(" ")
        val peers = syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId。使用 twin.peers 查看所有已知节点。")

        if (!com.mengpaw.kernel.security.PromptFirewall.isTrusted(peerId)) {
            return ExecutionResult.fail("未配对设备: $peerId。请先完成孪生配对（侧边栏 5 连击 MengPaw 框架图标）。")
        }

        val msg = com.mengpaw.kernel.acp.AcpMessage.twinDelegate(deviceId, peerId, task)
        val sent = acpTransport?.send(msg) ?: false
        return if (sent) {
            ExecutionResult.ok("任务已委派到 ${peer.agentName} ($peerId) — 使用 twin.status 查看状态")
        } else {
            ExecutionResult.fail("发送失败: 对端 ${peer.address}:${peer.port} 不可达。\n检查: 1) 对端是否在线 2) 网络是否互通 3) 防火墙是否拦截端口 ${com.mengpaw.kernel.ports.Ports.ACP}")
        }
    }

    private suspend fun cmdRoute(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val task = args.joinToString(" ")
        if (task.isBlank()) return ExecutionResult.fail("用法: twin.route <任务描述>")

        val context = appContext ?: return ExecutionResult.fail("无法获取设备上下文")
        val collector = TwinCapabilityCollector(context, deviceId, deviceName,
            mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION)
        val selfCard = collector.collect(llmProvider, pluginNames,
            currentSessionId = agentSessionId,
            isBusy = agentIsBusy)
        val peers = syncEngine.getPeers()
        val peerCards = peers.mapNotNull { peer ->
            peer.capabilityCard?.let { CapabilityCard.fromJson(it) }
        }

        val analysis = TwinRouter.route(task, selfCard, peerCards)
        return ExecutionResult.ok(analysis.summary)
    }

}
