// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * framework.connect/call 信任门禁回归测试 (v0.35.5) —
 * 未信任节点禁止连接/委派, 对齐"信任后即可 framework.connect"的引导语义。
 * 纯逻辑, 不触磁盘。
 */
class FrameworkTrustGateTest {

    private fun peer(trusted: Boolean, fingerprint: String = "mengpaw|aa:bb:cc:dd:ee:ff") =
        FrameworkPeerStore.FrameworkPeer(
            fingerprint = fingerprint,
            name = "书房平板",
            version = "0.35.4",
            address = "192.168.2.34",
            trusted = trusted
        )

    @Test
    fun 通讯录无节点返回未找到() {
        val r = frameworkTrustGate("不存在节点", null)
        assertTrue(r?.success == false)
        assertTrue(r!!.error.orEmpty().contains("无此节点"))
        assertEquals(com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND, r.errorCode)
    }

    @Test
    fun 未信任节点拒绝并引导信任命令() {
        val r = frameworkTrustGate("书房平板", peer(trusted = false))
        assertTrue(r?.success == false)
        assertTrue("拒绝信息应含未信任", r!!.error.orEmpty().contains("未信任"))
        assertTrue("应引导 framework.trust --yes", r.error.orEmpty().contains("framework.trust"))
        assertEquals(com.mengpaw.kernel.cli.ErrorCodes.ERR_PERMISSION_DENIED, r.errorCode)
    }

    @Test
    fun 已信任节点放行() {
        assertNull("已信任节点不应被门禁拦截", frameworkTrustGate("书房平板", peer(trusted = true)))
    }
}
