// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.acp.AcpMessage

/**
 * 框架通讯录配对请求引擎 (v0.35.1 框架发现流程调整) —
 * 发起请求 (直连 POST) / 同意 / 拒绝, 双向入册。
 */
object FrameworkPairEngine {

    /** 本机局域网 IPv4 (非回环) — 配对请求携带, 对方同意后据此入册。 */
    fun localIpv4(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { !it.isLoopback && it.isUp }
            ?.flatMap { it.inetAddresses.asSequence().toList() }
            ?.mapNotNull { (it as? java.net.Inet4Address)?.hostAddress }
            ?.firstOrNull { !it.startsWith("127.") }
    } catch (_: Exception) { null }

    private fun localFrom(): String = "mengpaw-${FrameworkIdentity.shortCode}"

    /** 发起配对请求 (发现节点/手动地址) — 直连 POST, 返回是否送达。 */
    suspend fun sendRequest(address: String, port: Int, displayName: String, fingerprint: String): Boolean {
        val server = com.mengpaw.kernel.namespace.AcpHolder.server
        val requestId = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val msg = AcpMessage.frameworkPairRequest(
            from = localFrom(),
            to = "*",
            requestId = requestId,
            fingerprint = fingerprint,
            displayName = displayName,
            address = localIpv4() ?: "",
            port = com.mengpaw.kernel.ports.Ports.ACP
        )
        return server.sendDirect(msg, address, port)
    }

    /** 同意请求 — 本地入册发起方 + 回发 ACCEPT (携带本机名片, 发起方据此入册本机)。 */
    suspend fun accept(req: FrameworkPairStore.PairRequest): Boolean {
        val host = req.fromAddress.substringBeforeLast(':').ifBlank { req.fromAddress }
        val peer = FrameworkPeerStore.FrameworkPeer(
            fingerprint = req.fromFingerprint.ifBlank {
                FrameworkPeerStore.computeFingerprint("mengpaw", host)
            },
            name = req.fromName,
            version = "配对请求",
            frameworkName = "MengPaw",
            address = host, port = req.fromPort,
            frameworkType = req.fromType,
            lastSeen = System.currentTimeMillis()
        )
        FrameworkPeerStore.save(peer)
        FrameworkPairStore.update(req.requestId) {
            it.copy(status = FrameworkPairStore.PairStatus.ACCEPTED, read = true)
        }
        val server = com.mengpaw.kernel.namespace.AcpHolder.server
        val msg = AcpMessage.frameworkPairResponse(
            from = localFrom(),
            to = "*",
            requestId = req.requestId,
            accepted = true,
            fingerprint = FrameworkIdentity.fingerprint,
            displayName = FrameworkIdentity.displayName.ifBlank { FrameworkIdentity.shortCode },
            address = localIpv4() ?: "",
            port = com.mengpaw.kernel.ports.Ports.ACP
        )
        return server.sendDirect(msg, host, req.fromPort)
    }

    /** 拒绝请求 — 仅回发 DECLINE。 */
    suspend fun decline(req: FrameworkPairStore.PairRequest): Boolean {
        FrameworkPairStore.update(req.requestId) {
            it.copy(status = FrameworkPairStore.PairStatus.DECLINED, read = true)
        }
        val server = com.mengpaw.kernel.namespace.AcpHolder.server
        val msg = AcpMessage.frameworkPairResponse(
            from = localFrom(),
            to = "*",
            requestId = req.requestId,
            accepted = false
        )
        return server.sendDirect(msg, req.fromAddress, req.fromPort)
    }
}
