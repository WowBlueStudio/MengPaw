// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.acp.AcpHandler
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.namespace.NotifyBus
import com.mengpaw.kernel.ports.Ports

/**
 * 框架通讯录配对请求 ACP handler (v0.35.1) —
 * REQUEST 落盘 pending (红点角标), ACCEPT/DECLINE 更新请求状态并通知。
 */
class FrameworkPairHandler : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(
        AcpMessageType.FRAMEWORK_PAIR_REQUEST,
        AcpMessageType.FRAMEWORK_PAIR_ACCEPT,
        AcpMessageType.FRAMEWORK_PAIR_DECLINE
    )

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        val type = try { AcpMessageType.valueOf(message.type) } catch (_: Exception) { return null }
        if (type !in supportedTypes) return null
        return try {
            when (type) {
                AcpMessageType.FRAMEWORK_PAIR_REQUEST -> handleRequest(message)
                AcpMessageType.FRAMEWORK_PAIR_ACCEPT -> handleAccept(message)
                AcpMessageType.FRAMEWORK_PAIR_DECLINE -> handleDecline(message)
                else -> null
            }
        } catch (e: Exception) {
            AcpResult(false, "framework_pair_error", e.message ?: "handler error")
        }
    }

    /** 收到配对请求 — 落盘 pending (红点) + banner 提醒。 */
    private fun handleRequest(msg: AcpMessage): AcpResult {
        val payload = try { org.json.JSONObject(msg.payload) } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_pair_request")
        val requestId = payload.optString("requestId", "")
        if (requestId.isBlank()) return AcpResult(false, "no_request_id")
        // 防重复: 同 requestId 已存在则忽略
        if (FrameworkPairStore.findByRequestId(requestId) != null) {
            return AcpResult(true, "duplicate_pair_request")
        }
        val req = FrameworkPairStore.PairRequest(
            requestId = requestId,
            fromFingerprint = payload.optString("fingerprint", ""),
            fromName = payload.optString("displayName", "").ifBlank {
                FrameworkPeerStore.shortCodeOf(payload.optString("fingerprint", "").ifBlank { msg.from })
            },
            fromAddress = payload.optString("address", ""),
            fromPort = payload.optInt("port", Ports.ACP),
            fromType = "mengpaw"
        )
        FrameworkPairStore.add(req)
        // v0.35.2 审查闭环: Agent 侧反馈通道 — inbox 提醒文件 (Agent 轮询可感知, 与 DelegateHandler 同模式)
        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX).also { it.mkdirs() }
            java.io.File(inbox, "pair_request_${System.currentTimeMillis()}.md").writeText(
                "收到框架配对请求: ${req.fromName} (${req.fromAddress}:${req.fromPort})\n" +
                    "待处理请求: framework.pair.ls; 同意/拒绝: framework.pair.accept/decline <requestId>\n" +
                    "或侧边栏通讯录点「添加框架」查看。"
            )
        } catch (_: Exception) {}
        try {
            NotifyBus.banner(
                "🔔 框架配对请求: ${req.fromName} 请求加入通讯录 — 点「添加框架」查看并同意",
                NotifyBus.NotifyLevel.INFO
            )
        } catch (_: Exception) {}
        return AcpResult(true, "pair_request_recorded")
    }

    /** 收到 ACCEPT — 发起方把接受方入册 (名片取自 payload), 请求标记已接受。 */
    private fun handleAccept(msg: AcpMessage): AcpResult {
        val payload = try { org.json.JSONObject(msg.payload) } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_pair_accept")
        val fingerprint = payload.optString("fingerprint", "")
        if (fingerprint.isBlank()) return AcpResult(false, "no_fingerprint")
        val displayName = payload.optString("displayName", "")
            .ifBlank { FrameworkPeerStore.shortCodeOf(fingerprint) }
        val address = payload.optString("address", "")
        val port = payload.optInt("port", Ports.ACP)
        FrameworkPeerStore.save(
            FrameworkPeerStore.FrameworkPeer(
                fingerprint = fingerprint,
                name = displayName,
                version = "配对请求",
                frameworkName = "MengPaw",
                address = address, port = port,
                lastSeen = System.currentTimeMillis()
            )
        )
        payload.optString("requestId", "").takeIf { it.isNotBlank() }?.let { rid ->
            FrameworkPairStore.findByRequestId(rid)?.let { req ->
                FrameworkPairStore.update(rid) {
                    it.copy(status = FrameworkPairStore.PairStatus.ACCEPTED, read = true)
                }
            }
        }
        try {
            NotifyBus.banner(
                "✅ 已与 ${displayName} 配对 — 已加入框架通讯录",
                NotifyBus.NotifyLevel.INFO
            )
        } catch (_: Exception) {}
        return AcpResult(true, "pair_accepted")
    }

    /** 收到 DECLINE — 请求标记拒绝。 */
    private fun handleDecline(msg: AcpMessage): AcpResult {
        val payload = try { org.json.JSONObject(msg.payload) } catch (_: Exception) { null }
        payload?.optString("requestId", "")?.takeIf { it.isNotBlank() }?.let { rid ->
            FrameworkPairStore.findByRequestId(rid)?.let { req ->
                FrameworkPairStore.update(rid) {
                    it.copy(status = FrameworkPairStore.PairStatus.DECLINED, read = true)
                }
                try {
                    NotifyBus.banner(
                        "❌ ${req.fromName} 拒绝了配对请求",
                        NotifyBus.NotifyLevel.WARN
                    )
                } catch (_: Exception) {}
            }
        }
        return AcpResult(true, "pair_declined")
    }
}
