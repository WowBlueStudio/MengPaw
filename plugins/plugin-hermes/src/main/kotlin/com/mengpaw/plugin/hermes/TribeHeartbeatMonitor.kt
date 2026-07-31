// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tribe 心跳监控 — ACP Heartbeat 存活检测。
 *
 * - 每 30s 广播 HEARTBEAT 消息
 * - 每 60s 清理 120s 未响应的对端
 * - 提供在线对端查询
 */
class TribeHeartbeatMonitor(
    private val agentName: String,
    private val agentId: String,
    private val acpServer: AcpServer?,
    private val scope: CoroutineScope
) {
    /** 已知对端及其活跃时间。 */
    private val peers = mutableMapOf<String, PeerInfo>()
    /** 心跳广播 Job。 */
    private var heartbeatJob: Job? = null
    /** 清理 Job。 */
    private var cleanupJob: Job? = null

    data class PeerInfo(
        val agentId: String,
        val agentName: String,
        var lastSeen: Long = System.currentTimeMillis(),
        var isOnline: Boolean = true
    )

    // ── Public API ─────────────────────────────────────────────

    /** 启动心跳广播和清理协程。 */
    fun start() {
        stop() // 确保先停止之前的
        heartbeatJob = scope.launch {
            while (isActive) {
                broadcastHeartbeat()
                delay(30_000L)
            }
        }
        cleanupJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                cleanupStalePeers()
            }
        }
    }

    /** 停止心跳和清理协程。 */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        cleanupJob?.cancel()
        cleanupJob = null
    }

    /** 由 [TribeAcpHandler] 收到 HEARTBEAT 时调用。 */
    fun onHeartbeat(from: String) {
        val peer = peers[from]
        if (peer != null) {
            peer.lastSeen = System.currentTimeMillis()
            peer.isOnline = true
        } else {
            // 首次发现的对端
            peers[from] = PeerInfo(agentId = from, agentName = from, lastSeen = System.currentTimeMillis())
        }
    }

    /** 获取当前在线对端。 */
    fun getOnlinePeers(): List<PeerInfo> {
        val now = System.currentTimeMillis()
        return peers.values.filter { now - it.lastSeen < 120_000 }
    }

    /**
     * Ping 指定 Agent。
     * @return true 如果对端在线（120s 内有心跳）
     */
    fun isPeerOnline(agentId: String): Boolean {
        val peer = peers[agentId] ?: return false
        return System.currentTimeMillis() - peer.lastSeen < 120_000
    }

    /** 重置所有对端为离线（在 tribe.stop 时调用）。 */
    fun markAllOffline() {
        peers.values.forEach { it.isOnline = false }
    }

    /** 手动注册一个对端（与 ACP 发现联动）。 */
    fun registerPeer(agentId: String, agentName: String) {
        if (agentId !in peers) {
            peers[agentId] = PeerInfo(agentId = agentId, agentName = agentName)
        }
    }

    // ── 内部方法 ──────────────────────────────────────────────

    private suspend fun broadcastHeartbeat() {
        try {
            val server = acpServer ?: return
            val msg = AcpMessage.heartbeat(agentId)
            server.sendViaTransport(msg)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeHeartbeatMonitor.broadcast")
        }
    }

    private fun cleanupStalePeers() {
        val now = System.currentTimeMillis()
        val staleIds = peers.filter { now - it.value.lastSeen > 120_000 }.keys
        staleIds.forEach { id ->
            peers[id]?.isOnline = false
        }
        // 超过 300s 无响应的完全移除
        peers.entries.removeAll { now - it.value.lastSeen > 300_000 }
    }
}
