// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.ports.Ports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 孪生对端注册表 — 从 TwinSyncEngine 拆分 (职责: 对端增删查 + 事件摄入)。
 *
 * 覆盖: 发现结果合并 / 手动添加 / 防御性副本 / 配对完成指引 /
 * 能力卡接收 (含配对请求落盘) / 委派任务摄入。
 * 依赖通过构造参数注入, 行为与拆分前完全一致。
 */
internal class TwinSyncPeerRegistry(
    private val scope: CoroutineScope,
    private val peers: java.util.concurrent.ConcurrentHashMap<String, TwinPeerInfo>,
    private val syncState: MutableStateFlow<TwinSyncState>,
    private val agentName: String,
    private val syncIntervalMs: () -> Long
) {

    /** Update the peer list from discovery. */
    fun updatePeers(discovered: List<TwinPeerInfo>) {
        discovered.forEach { peer ->
            val existing = peers[peer.peerId]
            if (existing == null || existing.address != peer.address) {
                peers[peer.peerId] = peer.copy(lastSeen = System.currentTimeMillis(), online = true)
            } else {
                existing.lastSeen = System.currentTimeMillis()
                existing.online = true
                if (existing.address != peer.address || existing.port != peer.port) {
                    existing.address = peer.address
                    existing.port = peer.port
                }
            }
        }
        syncState.value = syncState.value.copy(
            onlinePeers = peers.values.count { it.online },
            totalPeers = peers.size
        )
    }

    /** Manually add a peer by IP address (for networks where mDNS fails). */
    fun addManualPeer(address: String, port: Int = Ports.ACP, name: String? = null): TwinPeerInfo {
        val peerId = name ?: "manual-${address.replace(".", "-")}"
        // Clean up old manual entry for this address
        peers.values.removeAll { it.address == address && it.peerId.startsWith("manual-") }
        val peer = TwinPeerInfo(
            peerId = peerId, agentName = peerId,
            address = address, port = port,
            lastSeen = System.currentTimeMillis(), online = true
        )
        peers[peerId] = peer
        return peer
    }

    /** Get known peers (defensive copy). */
    fun getPeers(): List<TwinPeerInfo> = peers.values.toList().map { it.copy() }

    /** Called after pairing is established with a peer. */
    fun onPairingEstablished(peerId: String) {
        peers[peerId]?.let {
            it.lastSeen = System.currentTimeMillis()
            it.lastSyncAt = System.currentTimeMillis()
            it.online = true
        }
        // Inject post-pairing guidance into agent workspace
        scope.launch { injectPairingGuidance(peerId) }
    }

    // ── Rate limiting for capability announces ──────────────────────
    private val lastCapabilityAnnounce = mutableMapOf<String, Long>()

    /** Called when a peer announces its capabilities. */
    fun onCapabilityReceived(peerId: String, cardJson: String) {
        peers[peerId]?.let {
            it.capabilityCard = cardJson
            it.lastSeen = System.currentTimeMillis()
            it.online = true
        } ?: run {
            // New peer discovered via capability announce
            val card = try { CapabilityCard.fromJson(cardJson) } catch (_: Exception) { null }
            peers[peerId] = TwinPeerInfo(
                peerId = peerId,
                agentName = card?.deviceName ?: peerId.take(12),
                address = "", port = Ports.ACP,
                lastSeen = System.currentTimeMillis(), online = true,
                capabilityCard = cardJson
            )
        }

        // Rate limit: max 1 inbox entry per peer per 30 seconds
        val lastTime = lastCapabilityAnnounce[peerId] ?: 0L
        if (System.currentTimeMillis() - lastTime < 30_000) return
        lastCapabilityAnnounce[peerId] = System.currentTimeMillis()

        // P0.1 FIX: Only write pairing request to inbox if peer is trusted.
        // Unpaired devices are still registered (for discovery) but won't
        // appear as pending pair requests in the UI.
        val isTrusted = com.mengpaw.kernel.security.PromptFirewall.isTrusted(peerId)
        if (!isTrusted) {
            android.util.Log.i("MengPawTwin", "跳过未配对设备配对请求: $peerId")
            return
        }

        // Write pairing request to inbox file — UI polls this
        val card = try { CapabilityCard.fromJson(cardJson) } catch (_: Exception) { null }
        try {
            val inbox = File(DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            val file = File(inbox, "twin_pair_${peerId.take(16)}.json")
            // P3.4 FIX: Use kotlinx.serialization instead of org.json
            val jsonStr = buildJsonObject {
                put("peerId", peerId)
                put("deviceName", card?.deviceName ?: peerId.take(12))
                put("deviceModel", card?.deviceModel ?: "")
                put("agentName", card?.deviceName ?: "")
                put("receivedAt", System.currentTimeMillis())
                put("capabilityCard", cardJson)
                put("protocolVersion", card?.protocolVersion ?: "0.1")
            }.toString()
            val tmp = File(inbox, "twin_pair_${peerId.take(16)}.tmp")
            tmp.writeText(jsonStr)
            tmp.renameTo(file)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.onCapability")
        }
    }

    /** Called when a peer delegates a task. */
    fun onTwinDelegateReceived(
        fromPeerId: String, task: String, requirements: String,
        delegateId: String = "", callbackAddress: String = "", callbackPort: Int = 0
    ) {
        if (!com.mengpaw.kernel.security.PromptFirewall.isTrusted(fromPeerId)) {
            android.util.Log.w("MengPawTwin", "拒绝未配对设备的委派任务: $fromPeerId")
            return
        }
        // v0.36 舰队闭环: 记录回传地址 (对端 fleet.reply 用), inbox 落盘含回传指引
        if (delegateId.isNotBlank()) {
            try {
                com.mengpaw.kernel.agent.FleetRuntimeStore.recordIncoming(
                    delegateId, task, fromPeerId, callbackAddress, callbackPort)
            } catch (_: Exception) {}
        }
        scope.launch {
            val inboxDir = File(DataPaths.AGENTS, "$agentName/inbox")
            if (!inboxDir.exists()) inboxDir.mkdirs()
            val taskFile = File(inboxDir, "twin_delegate_${System.currentTimeMillis()}.md")
            taskFile.writeText(
                buildString {
                    appendLine("# 孪生任务委派")
                    appendLine("> 来自: $fromPeerId")
                    appendLine("> 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                    if (requirements.isNotBlank()) appendLine("> 能力需求: $requirements")
                    if (delegateId.isNotBlank()) {
                        appendLine("> 委派 ID: $delegateId")
                        appendLine()
                        appendLine("执行完成后必须回传结果: `fleet.reply $delegateId <结果文本>`")
                    }
                    appendLine()
                    appendLine(task)
                }
            )
        }
    }

    /** Inject post-pairing guidance into the agent workspace. */
    private fun injectPairingGuidance(peerId: String) {
        try {
            val peer = peers[peerId] ?: return
            val guidanceFile = File(DataPaths.AGENTS, "$agentName/inbox/twin_paired_${System.currentTimeMillis()}.md")
            guidanceFile.parentFile?.mkdirs()
            val tmp = File(guidanceFile.parentFile, "twin_paired.tmp")
            tmp.writeText(buildString {
                appendLine("# 🧠 记忆孪生配对完成")
                appendLine()
                appendLine("## 配对节点")
                appendLine("- 设备: ${peer.agentName}")
                appendLine("- 地址: ${peer.address}:${peer.port}")
                appendLine("- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine()
                appendLine("## 自动同步")
                appendLine("- 同步周期: 每 ${syncIntervalMs() / 1000} 秒")
                appendLine("- 同步内容: 记忆 | 身份文档 | 梦境记录")
                appendLine("- 验证方式: SHA-256 哈希链")
                appendLine()
                appendLine("## 常用命令")
                appendLine("- `twin.status` — 查看孪生状态和同步阶段")
                appendLine("- `twin.peers` — 查看所有已发现节点")
                appendLine("- `twin.sync` — 手动触发工作区同步 (接收/发送/冲突数)")
                appendLine("- `twin.delegate <peer> <task>` — 委派任务到对端")
                appendLine("- `twin.capabilities --all` — 对比所有节点能力")
                appendLine("- `twin.sync` — 手动触发全量同步")
                appendLine()
                appendLine("## 安全提示")
                appendLine("- 仅在受信任的个人设备间使用孪生配对")
                appendLine("- 在侧边栏框架名片中可随时解除孪生")
                appendLine("- 所有记忆通过 AES-256 加密通道传输")
            })
            tmp.renameTo(guidanceFile)
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            android.util.Log.i("MengPawTwin", "已注入配对指引到工作区")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.injectPairingGuidance")
        }
    }
}
