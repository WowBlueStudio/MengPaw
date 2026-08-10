// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AcpServer.normalizeHostForUrl 回归测试 (v0.35.5) —
 * sendDirect 直连地址规范化: IPv4/主机名原样, IPv6 加方括号 + scope 百分号编码,
 * 非法地址 (协议/路径/空格/非白名单字符) 拒绝。
 */
class AcpServerHostNormalizeTest {

    private val server = AcpServer(AgentProfile(), sharedSecret = "test")

    @Test
    fun IPv4原样返回() {
        assertEquals("192.168.2.34", server.normalizeHostForUrl("192.168.2.34"))
        assertEquals("192.168.2.34", server.normalizeHostForUrl("  http://192.168.2.34  "))
    }

    @Test
    fun 主机名原样返回() {
        assertEquals("my-host.local", server.normalizeHostForUrl("my-host.local"))
    }

    @Test
    fun IPv6加方括号() {
        assertEquals("[fe80::3004:bcff:fe44:23e2]", server.normalizeHostForUrl("fe80::3004:bcff:fe44:23e2"))
        // 已带方括号不重复包裹
        assertEquals("[2001:db8::1]", server.normalizeHostForUrl("[2001:db8::1]"))
    }

    @Test
    fun IPv6带接口scope百分号编码() {
        assertEquals("[fe80::1%25wlan0]", server.normalizeHostForUrl("fe80::1%wlan0"))
    }

    @Test
    fun 非法地址拒绝() {
        assertNull(server.normalizeHostForUrl(""))
        assertNull(server.normalizeHostForUrl("https://evil.com/path"))
        assertNull(server.normalizeHostForUrl("192.168.2.34/path"))
        assertNull(server.normalizeHostForUrl("host with space"))
        assertNull(server.normalizeHostForUrl("javascript:alert(1)"))
    }
}
