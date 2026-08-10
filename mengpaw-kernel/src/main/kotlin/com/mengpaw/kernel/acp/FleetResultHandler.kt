// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.FleetRuntimeStore
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

/**
 * 舰队委派结果回传 handler (v0.36 深度进化) — 对端执行完发送 FLEET_RESULT,
 * 指挥舰侧核对 delegateId 归属后更新任务状态 (DONE/FAILED + 结果)。
 */
class FleetResultHandler : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(AcpMessageType.FLEET_RESULT)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        val payload = try { json.parseToJsonElement(message.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_fleet_result")
        val delegateId = payload["delegateId"]?.jsonPrimitive?.content ?: return AcpResult(false, "no_delegate_id")
        val result = payload["result"]?.jsonPrimitive?.content ?: ""
        val success = payload["success"]?.jsonPrimitive?.boolean ?: false

        // 校验 delegateId 归属 (发起方才有该记录) — 防伪造/横向污染
        val recorded = FleetRuntimeStore.markDone(delegateId, result, message.from, success)
        return if (recorded) AcpResult(true, "fleet_result_recorded")
        else AcpResult(false, "unknown_delegate", "delegateId 不存在或已过期")
    }
}
