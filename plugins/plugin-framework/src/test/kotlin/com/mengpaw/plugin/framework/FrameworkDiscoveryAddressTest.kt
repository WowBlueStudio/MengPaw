// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * FrameworkDiscovery.preferIpv4 回归测试 (v0.35.5) —
 * mDNS 多地址 (IPv4 + IPv6 link-local) 时优先选 IPv4, 无 IPv4 才回退 IPv6。
 */
class FrameworkDiscoveryAddressTest {

    private fun ipv4(s: String) = InetAddress.getByName(s) as Inet4Address
    private fun ipv6(s: String) = InetAddress.getByName(s) as Inet6Address

    @Test
    fun 多地址优先IPv4() {
        val addrs = listOf(ipv6("fe80::3004:bcff:fe44:23e2"), ipv4("192.168.2.34"))
        assertEquals("192.168.2.34", preferIpv4(addrs)?.hostAddress)
    }

    @Test
    fun 无IPv4回退IPv6() {
        val addrs = listOf(ipv6("fe80::1"))
        assertEquals("fe80:0:0:0:0:0:0:1", preferIpv4(addrs)?.hostAddress)
    }

    @Test
    fun 空列表返回null() {
        assertNull(preferIpv4(emptyList()))
    }
}
