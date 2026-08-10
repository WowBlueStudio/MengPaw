// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.FleetCapability
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

/**
 * 舰队能力上报 handler (v0.36) —
 * - request=true: 指挥所请求上报 → 本机经 [FleetCapabilityRegistry] 生成能力卡回传
 * - 能力卡到达: 缓存到 [FleetCapability.cache], 供指挥所 fleet.scan 写 Notes
 */
class FleetCapabilityHandler : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(AcpMessageType.FLEET_CAPABILITY)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        val payload = try { json.parseToJsonElement(message.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_fleet_capability")
        val isRequest = payload["request"]?.jsonPrimitive?.boolean ?: false
        val capabilityJson = payload["capability"]?.toString() ?: ""

        if (isRequest) {
            // 指挥所请求上报: 本机生成能力卡并回传 (回传地址随请求携带)
            val callbackAddress = payload["callbackAddress"]?.jsonPrimitive?.content ?: ""
            val callbackPort = payload["callbackPort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val cardJson = FleetCapabilityRegistry.provider?.invoke()
            if (cardJson != null && callbackAddress.isNotBlank()) {
                val reply = AcpMessage.fleetCapability(
                    from = FleetCapabilityRegistry.localPeerId?.invoke() ?: message.to,
                    to = message.from, capabilityJson = cardJson)
                server.sendDirect(reply, callbackAddress, callbackPort)
            }
            return AcpResult(true, "capability_request_received")
        }

        if (capabilityJson.isNotBlank() && FleetCapability.fromJson(capabilityJson) != null) {
            FleetCapability.cache[message.from] = capabilityJson
            return AcpResult(true, "capability_recorded", message.from)
        }
        return AcpResult(false, "invalid_capability_card")
    }
}

/** 能力卡生成注入点 — shell 层注册 Android/连接器环境信息, kernel 保持平台无关。 */
object FleetCapabilityRegistry {
    /** 返回本机能力卡 JSON (null = 不可用)。 */
    @Volatile var provider: (() -> String)? = null
    /** 返回本机 ACP 身份 (mengpaw-<短码>) — 回传消息的 from。 */
    @Volatile var localPeerId: (() -> String)? = null
}
