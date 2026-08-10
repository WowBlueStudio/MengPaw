// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.agent.FleetCapability
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FleetCapabilityHandler 回归测试 (v0.36) — 能力卡记录进缓存 (指挥所收集)。
 */
class FleetCapabilityHandlerTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "fleet_cap_test-${System.nanoTime()}")

    @Before
    fun init() {
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)
        FleetCapability.cache.clear()
    }

    @After
    fun cleanup() {
        FleetCapability.cache.clear()
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    fun 能力卡记录进缓存() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FleetCapabilityHandler()
        val card = FleetCapability("PC 坦克机", "codex", "1.0", "PC-Linux",
            "workstation", 16, 32768, 102400, listOf("node", "python"))
        val msg = AcpMessage.fleetCapability("mengpaw-xyz", "mengpaw-abc", card.toJson())
        val result = runBlocking { handler.handle(msg, server) }
        assertTrue(result?.success == true)
        assertEquals("mengpaw-xyz", FleetCapability.cache.keys.first())
        assertEquals("PC 坦克机", FleetCapability.fromJson(FleetCapability.cache.values.first())?.frameworkName)
    }

    @Test
    fun 非法能力卡拒绝() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FleetCapabilityHandler()
        // 合法 JSON 但缺少 FleetCapability 必填字段 — handler 层拒绝
        val msg = AcpMessage.fleetCapability("peer", "to", """{"foo":"bar"}""")
        val result = runBlocking { handler.handle(msg, server) }
        assertTrue(result?.success == false)
        assertTrue(FleetCapability.cache.isEmpty())
    }
}
