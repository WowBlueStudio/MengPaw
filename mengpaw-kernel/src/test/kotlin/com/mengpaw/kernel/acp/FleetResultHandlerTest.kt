// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.agent.FleetRuntimeStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FLEET_RESULT 协议闭环测试 (v0.36) —
 * 执行方回传 → FleetResultHandler 校验 delegateId 归属 → 指挥舰状态回收。
 */
class FleetResultHandlerTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "fleet_result_test-${System.nanoTime()}")

    @Before
    fun init() {
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)
        FleetRuntimeStore.clear()
    }

    @After
    fun cleanup() {
        FleetRuntimeStore.clear()
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    fun 结果回传闭环更新指挥舰任务() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FleetResultHandler()
        FleetRuntimeStore.startTask("fleet-r1", "构建 APK", "坦克机", "mengpaw-abc-def")

        val msg = AcpMessage.fleetResult(
            from = "mengpaw-xyz-789", to = "mengpaw-abc-def",
            delegateId = "fleet-r1", result = "APK 构建成功 (9.9MB)", success = true)
        val result = runBlocking { handler.handle(msg, server) }
        assertTrue(result?.success == true)
        assertEquals("DONE", FleetRuntimeStore.find("fleet-r1")?.status)
        assertEquals("APK 构建成功 (9.9MB)", FleetRuntimeStore.find("fleet-r1")?.result)
    }

    @Test
    fun 未知委派ID拒绝() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FleetResultHandler()
        val msg = AcpMessage.fleetResult("peer", "to", "ghost", "x", true)
        val result = runBlocking { handler.handle(msg, server) }
        assertTrue(result?.success == false)
    }

    @Test
    fun 协议工厂payload字段完整() {
        val msg = AcpMessage.fleetResult("mengpaw-a", "mengpaw-b", "fleet-r3", "完成", true)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(msg.payload).jsonObject
        assertEquals("fleet-r3", payload["delegateId"]?.jsonPrimitive?.content)
        assertEquals("完成", payload["result"]?.jsonPrimitive?.content)
        assertEquals(true, payload["success"]?.jsonPrimitive?.boolean)
        assertEquals(AcpMessageType.FLEET_RESULT.name, msg.type)
        assertEquals("fleet-r3", msg.requestId)
    }
}
