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
 * Memory Twin Plugin — cross-device Agent memory synchronization.
 *
 * Implements the [Plugin] interface to contribute `twin.*` CLI commands
 * to the MengPaw framework. Uses hash-chain ledger + ACP P2P protocol
 * for distributed, tamper-evident memory synchronization.
 *
 * ## Architecture
 * - [TwinLedger] / [TwinLedgerStore] — hash chain data model & persistence
 * - [TwinSyncEngine] — sync state machine (HEAD→PULL→BATCH→ACK) + heartbeat + QoS
 * - [TwinAcpHandler] — ACP message handler (first AcpHandler implementation)
 * - [TwinDiscovery] — Android NSD LAN peer discovery
 * - [TwinPairingEngine] — short-code verification pairing protocol
 * - [TwinCapabilityCollector] — device capability card generation
 * - [TwinRouter] — capability-aware task routing
 * - [TwinIdentity] — soul/profile identity doc sync
 * - [TwinDreamSync] — dream event ledger integration
 */
class MemoryTwinPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "memory-twin-plugin",
        name = "记忆孪生",
        version = "0.2.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "跨设备记忆孪生同步 — 哈希链账本 + ACP P2P + 能力感知路由 + 心跳保活",
        minCoreVersion = "0.12.0",
        commands = listOf(
            "twin.start", "twin.stop", "twin.status",
            "twin.peers", "twin.peer.info", "twin.peer.add",
            "twin.pair", "twin.unpair",
            "twin.sync", "twin.sync.auto", "twin.sync.qos",
            "twin.capabilities",
            "twin.delegate", "twin.route",
            "twin.ledger.show", "twin.ledger.verify",
            "twin.ledger.diff", "twin.ledger.stats",
            "twin.identity.push", "twin.identity.pull",
            "twin.identity.diff", "twin.identity.merge",
            "twin.dream.sync", "twin.dream.history"
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
        val isActivated: Boolean get() = acpServer != null

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
        "ledger.show" to ::cmdLedgerShow,
        "ledger.verify" to ::cmdLedgerVerify,
        "ledger.diff" to ::cmdLedgerDiff,
        "ledger.stats" to ::cmdLedgerStats,
        "identity.push" to ::cmdIdentityPush,
        "identity.pull" to ::cmdIdentityPull,
        "identity.diff" to ::cmdIdentityDiff,
        "identity.merge" to ::cmdIdentityMerge,
        "dream.sync" to ::cmdDreamSync,
        "dream.history" to ::cmdDreamHistory
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

        syncEngine = TwinSyncEngine(
            serverSupplier = { acpServer }, transportSupplier = { acpTransport },
            agentName = agentName, deviceId = deviceId, deviceName = deviceName
        )
        acpHandler = TwinAcpHandler(syncEngine)
        server.registerHandler(acpHandler)
        TwinIdentity.snapshot(agentName)

        val context = appContext
        if (context != null) {
            discovery = TwinDiscovery(context, deviceId, agentName)
            discovery?.start()
        }

        isRunning = true
        syncEngine.startAutoSync()

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
        isRunning = false
        return true
    }

    private suspend fun cmdStatus(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.ok("孪生服务: 未启动。使用 twin.start 启动。")

        val state = syncEngine.syncState.value
        val stats = TwinLedgerStore.stats()
        return ExecutionResult.ok(buildString {
            appendLine("## 孪生状态")
            appendLine("- 服务: 运行中")
            appendLine("- 设备: $deviceName")
            appendLine("- 指纹: ${deviceId.take(16)}...")
            appendLine("- 协议版本: 0.2")
            appendLine("- 同步阶段: ${state.phase}")
            appendLine("- 在线节点: ${state.onlinePeers}/${state.totalPeers}")
            appendLine("- QoS: ${syncEngine.qosLevel.name}")
            appendLine("- 上次同步: ${
                if (state.lastSyncAt > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(state.lastSyncAt)) else "从未"
            }")
            if (state.lastEntriesReceived > 0) {
                appendLine("- 上次接收: ${state.lastEntriesReceived} 条")
            }
            appendLine("- 账本条目: ${stats.totalEntries}")
            appendLine("- 账本验证: ${if (stats.verified) "✅ 完整" else "❌ 损坏"}")
            appendLine("- 来源设备: ${stats.devices.keys.joinToString()}")
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
                val syncState = if (p.lastAckedHash != null) "已同步" else "待同步"
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
        val port = args.getOrNull(1)?.toIntOrNull() ?: 9876
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

    // ── Sync commands ─────────────────────────────────────────────

    private suspend fun cmdSync(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")

        val peerId = args.getOrNull(0)
        val result: TwinSyncResult = if (peerId != null) {
            syncEngine.syncWithPeer(peerId)
        } else {
            val results = syncEngine.syncWithAllPeers()
            if (results.isEmpty()) TwinSyncResult(0, "无在线节点可同步", "使用 twin.peers 查看节点列表，确保对端在线")
            else if (results.all { it.entriesReceived == 0 && it.error != null }) results.first()
            else TwinSyncResult(results.sumOf { it.entriesReceived }, null, null)
        }

        if (result.error != null) {
            return ExecutionResult.fail(buildString {
                appendLine("同步失败: ${result.error}")
                if (result.suggestion != null) appendLine("建议: ${result.suggestion}")
            })
        }
        return ExecutionResult.ok(buildString {
            appendLine("同步完成")
            if (result.entriesReceived > 0) {
                appendLine("- 接收条目: ${result.entriesReceived}")
                appendLine("- 使用 twin.ledger.stats 查看更新后的统计")
            } else {
                appendLine("- 无新条目 (已是最新)")
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
                val collector = TwinCapabilityCollector(context, deviceId, deviceName)
                val card = collector.collect(llmProvider, pluginNames)
                ExecutionResult.ok(card.toJson())
            }
            "--all" -> {
                val collector = TwinCapabilityCollector(context, deviceId, deviceName)
                val selfCard = collector.collect(llmProvider, pluginNames)
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
            ExecutionResult.fail("发送失败: 对端 ${peer.address}:${peer.port} 不可达。\n检查: 1) 对端是否在线 2) 网络是否互通 3) 防火墙是否拦截端口 9876")
        }
    }

    private suspend fun cmdRoute(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val task = args.joinToString(" ")
        if (task.isBlank()) return ExecutionResult.fail("用法: twin.route <任务描述>")

        val context = appContext ?: return ExecutionResult.fail("无法获取设备上下文")
        val collector = TwinCapabilityCollector(context, deviceId, deviceName)
        val selfCard = collector.collect(llmProvider, pluginNames)
        val peers = syncEngine.getPeers()
        val peerCards = peers.mapNotNull { peer ->
            peer.capabilityCard?.let { CapabilityCard.fromJson(it) }
        }

        val analysis = TwinRouter.route(task, selfCard, peerCards)
        return ExecutionResult.ok(analysis.summary)
    }

    // ── Ledger commands ───────────────────────────────────────────

    private suspend fun cmdLedgerShow(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val limit = args.getOrNull(0)?.toIntOrNull() ?: 20
        val entries = TwinLedgerStore.loadTail(limit)
        if (entries.isEmpty()) return ExecutionResult.ok("(账本为空)")

        return ExecutionResult.ok(buildString {
            appendLine("# 记忆账本 (最近 $limit 条)")
            appendLine()
            entries.reversed().forEach { entry ->
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(entry.timestamp))
                val typeIcon = when (entry.type) {
                    EntryType.MEMORY -> "📝"
                    EntryType.DREAM -> "🌙"
                    EntryType.SOUL_UPDATE -> "🧠"
                    EntryType.PROFILE_UPDATE -> "👤"
                    EntryType.IDENTITY_SYNC -> "🔗"
                    EntryType.CAPABILITY_UPDATE -> "📊"
                }
                appendLine("### $typeIcon ${entry.id}")
                appendLine("- 时间: $date | 设备: ${entry.deviceName} | 类型: ${entry.type}")
                appendLine("- 哈希: ${entry.hash.take(12)}...")
                appendLine("- 内容: ${entry.content.take(200)}")
                appendLine()
            }
        })
    }

    private suspend fun cmdLedgerVerify(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val result = TwinLedgerStore.verify()
        return ExecutionResult.ok(buildString {
            appendLine("## 账本完整性验证")
            appendLine("- 总条目: ${result.entryCount}")
            appendLine("- 验证结果: ${if (result.valid) "✅ 完整" else "❌ 损坏"}")
            if (!result.valid) {
                appendLine("- 损坏位置: 第 ${result.firstInvalidIndex} 条")
                appendLine("- 原因: ${result.firstInvalidReason}")
                appendLine("- 建议: 从其他已配对设备执行 twin.sync 恢复数据")
            }
            appendLine("- 创世哈希: ${result.genesisHash?.take(16) ?: "N/A"}...")
            appendLine("- 最新哈希: ${result.latestHash?.take(16) ?: "N/A"}...")
            appendLine("- 来源设备: ${result.devices.joinToString()}")
        })
    }

    private suspend fun cmdLedgerDiff(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.ledger.diff <peer-id>")
        val peers = syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId")

        val localCount = TwinLedgerStore.count()
        val localLatest = TwinLedgerStore.latest()
        val peerAcked = peer.lastAckedHash
        return ExecutionResult.ok(buildString {
            appendLine("## 账本差异")
            appendLine("- 本机: $localCount 条, 最新 ${localLatest?.hash?.take(12) ?: "N/A"}...")
            appendLine("- ${peer.agentName}: ACK=${peerAcked?.take(12) ?: "无"}...")
            if (peerAcked != null && localLatest != null && peerAcked != localLatest.hash) {
                appendLine("- 状态: 🔄 有未同步条目")
                appendLine("- 建议: twin.sync $peerId")
            } else if (peerAcked != null && localLatest != null) {
                appendLine("- 状态: ✅ 已同步")
            } else {
                appendLine("- 状态: ⚠️ 无法判断, 请手动同步: twin.sync $peerId")
            }
        })
    }

    private suspend fun cmdLedgerStats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val stats = TwinLedgerStore.stats()
        return ExecutionResult.ok(buildString {
            appendLine("## 账本统计")
            appendLine("- 总条目: ${stats.totalEntries}")
            appendLine("- 文件大小: ${"%.1f".format(stats.fileSizeBytes / 1024.0)} KB")
            appendLine("- 验证状态: ${if (stats.verified) "✅" else "❌"}")
            appendLine()
            appendLine("### 设备分布")
            stats.devices.forEach { (device, count) ->
                appendLine("- $device: $count 条")
            }
            appendLine()
            appendLine("### 类型分布")
            stats.typeDistribution.forEach { (type, count) ->
                appendLine("- $type: $count 条")
            }
        })
    }

    // ── Identity commands ─────────────────────────────────────────

    private suspend fun cmdIdentityPush(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.fail("孪生服务未启动")
        val entries = TwinIdentity.pushIdentityDocs(agentName, deviceId, deviceName)
        if (entries.isEmpty()) return ExecutionResult.ok("无身份文档变更")
        return ExecutionResult.ok("身份文档已推送 — ${entries.size} 条账本条目\n下次同步时自动传播到所有节点")
    }

    private suspend fun cmdIdentityPull(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
        return if (peerId != null) {
            syncEngine.syncWithPeer(peerId)
            ExecutionResult.ok("已从 $peerId 拉取身份文档")
        } else {
            syncEngine.syncWithAllPeers()
            ExecutionResult.ok("已从所有节点拉取")
        }
    }

    private suspend fun cmdIdentityDiff(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.identity.diff <peer-id>")
        val peers = syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
        val diff = TwinIdentity.diffIdentityDocs(agentName, peer?.capabilityCard)
        return ExecutionResult.ok(diff)
    }

    private suspend fun cmdIdentityMerge(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.identity.merge <peer-id>")
        return ExecutionResult.ok(
            "⚠️ 身份文档合并需要人工审查。\n" +
            "在侧边栏 → 孪生管理 → 身份同步 → 查看差异 → 确认合并。"
        )
    }

    // ── Dream commands ────────────────────────────────────────────

    private suspend fun cmdDreamSync(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.fail("孪生服务未启动")
        val dreamEntries = TwinLedgerStore.byType(EntryType.DREAM)
        if (dreamEntries.isEmpty()) return ExecutionResult.ok("(无梦境记录需要同步)")

        syncEngine.syncWithAllPeers()
        return ExecutionResult.ok("梦境同步已触发 — ${dreamEntries.size} 条记录")
    }

    private suspend fun cmdDreamHistory(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val limit = args.getOrNull(0)?.toIntOrNull() ?: 10
        val history = TwinDreamSync.getDreamHistory(limit)
        return ExecutionResult.ok(history)
    }
}
