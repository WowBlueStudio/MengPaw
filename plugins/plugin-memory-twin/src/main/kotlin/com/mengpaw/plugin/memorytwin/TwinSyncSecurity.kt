// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.acp.AcpTransport
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 孪生解绑/安全处理 — 从 TwinSyncEngine 拆分 (职责: REVOKE 处理与广播)。
 *
 * 依赖通过构造参数注入, 行为与拆分前完全一致。
 */
internal class TwinSyncSecurity(
    private val peers: java.util.concurrent.ConcurrentHashMap<String, TwinPeerInfo>,
    private val syncState: MutableStateFlow<TwinSyncState>,
    private val transportSupplier: () -> AcpTransport?,
    private val deviceId: String
) {

    /**
     * Called when a revoke command is received from a peer.
     * Removes trust, marks entries as compromised, writes audit log.
     */
    fun onRevokeReceived(peerId: String) {
        // 1. Remove trust
        com.mengpaw.kernel.security.PromptFirewall.untrust(peerId)
        // 2. Remove the key material
        try {
            val keyFile = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$peerId.key")
            if (keyFile.exists()) keyFile.delete()
        } catch (_: Exception) {}
        // 3. (v0.22.0) 账本已移除 — 工作区文档由各设备本地持有, 解绑不标记
        android.util.Log.w("MengPawTwin", "孪生解绑: peer=$peerId")
        // 4. Write audit log
        try {
            val auditFile = java.io.File(com.mengpaw.kernel.DataPaths.TWIN_AUDIT)
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date()) +
                " | REVOKE | peer=$peerId\n"
            )
        } catch (_: Exception) {}
        // 5. Remove peer from peer list
        peers.remove(peerId)
        // 6. Update state
        syncState.value = syncState.value.copy(
            onlinePeers = peers.values.count { it.online },
            totalPeers = peers.size
        )
    }

    /**
     * Send a revoke command to all online peers.
     * Used by twin.lost CLI to broadcast device loss.
     */
    suspend fun broadcastRevoke(targetPeerId: String) {
        val transport = transportSupplier() ?: return
        val peersToNotify = peers.values.filter { it.online && it.peerId != targetPeerId }
        peersToNotify.forEach { peer ->
            try {
                val msg = com.mengpaw.kernel.acp.AcpMessage.revoke(deviceId, peer.peerId, targetPeerId)
                transport.send(msg)
                android.util.Log.i("MengPawTwin", "已发送解绑广播到 ${peer.peerId}")
            } catch (e: Exception) {
                android.util.Log.w("MengPawTwin", "发送解绑广播到 ${peer.peerId} 失败: ${e.message}")
            }
        }
    }
}
