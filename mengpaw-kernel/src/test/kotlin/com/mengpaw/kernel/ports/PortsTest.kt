// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.ports

import org.junit.Assert.*
import org.junit.Test

class PortsTest {

    @Test
    fun `ALL contains all 7 ports with unique values`() {
        assertEquals(7, Ports.ALL.size)
        val values = Ports.ALL.map { it.port }
        assertTrue("端口必须唯一: $values", values.distinct().size == values.size)
    }

    @Test
    fun `only ACP is inbound`() {
        val inbound = Ports.ALL.filter { it.direction == Ports.Direction.INBOUND }
        assertEquals(listOf(Ports.ACP), inbound.map { it.port })
    }

    @Test
    fun `ACP constant matches kernel listener default`() {
        assertEquals(9876, Ports.ACP)
    }

    @Test
    fun `describe zh contains both sections and all ports`() {
        val md = Ports.describe("zh")
        assertTrue(md.contains("## 网络端口"))
        assertTrue(md.contains("本机监听"))
        assertTrue(md.contains("外部服务默认端口"))
        Ports.ALL.forEach { assertTrue("缺少端口 ${it.port}", md.contains("${it.port}")) }
    }

    @Test
    fun `describe en contains both sections`() {
        val md = Ports.describe("en")
        assertTrue(md.contains("## Network Ports"))
        assertTrue(md.contains("Locally listened"))
        assertTrue(md.contains("External service default ports"))
    }

    @Test
    fun `outbound ports are configurable, inbound is not`() {
        Ports.ALL.filter { it.direction == Ports.Direction.OUTBOUND }.forEach {
            assertTrue("${it.port} 应可配置", it.configurable)
            assertTrue("${it.port} 应说明配置途径", it.configVia.isNotBlank())
        }
        Ports.ALL.filter { it.direction == Ports.Direction.INBOUND }.forEach {
            assertFalse("${it.port} 不应可配置", it.configurable)
        }
    }
}
