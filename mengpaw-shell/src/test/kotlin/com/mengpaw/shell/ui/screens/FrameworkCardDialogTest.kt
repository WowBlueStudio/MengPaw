// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrameworkCardDialog.kt 的 peerFromContact 回归测试 —
 * 手机端 ACP 配对但未入册的框架点"信任框架"时, 由联系人合成通讯录条目。
 * 纯逻辑, 不触磁盘。
 */
class FrameworkCardDialogTest {

    @Test
    fun ACP联系人合成peer_地址拆分端口与信任置位() {
        val contact = FrameworkContact(
            name = "我的平板",
            address = "192.168.1.5:9881",
            online = true,
            trusted = true,
            agents = listOf("MengPaw"),
            frameworkType = "mengpaw",
            discovered = false
        )
        val peer = peerFromContact(contact)
        assertEquals("我的平板", peer.name)
        assertEquals("192.168.1.5", peer.address)
        assertEquals(9881, peer.port)
        assertTrue("信任动作必须置 trusted", peer.trusted)
        assertEquals("mengpaw|192.168.1.5:9881", peer.fingerprint)
    }

    @Test
    fun 已有指纹的联系人保留指纹() {
        val contact = FrameworkContact(
            name = "桌面端",
            address = "10.0.0.8:9881",
            online = false,
            trusted = false,
            agents = emptyList(),
            frameworkType = "mengpaw",
            fingerprint = "mengpaw|aa:bb:cc:dd:ee:ff",
            discovered = false
        )
        val peer = peerFromContact(contact)
        assertEquals("mengpaw|aa:bb:cc:dd:ee:ff", peer.fingerprint)
        assertEquals("10.0.0.8", peer.address)
        assertEquals(9881, peer.port)
    }

    @Test
    fun 无端口地址回退ACP默认端口() {
        val contact = FrameworkContact(
            name = "无端口节点",
            address = "192.168.1.9",
            online = false,
            trusted = false,
            agents = emptyList()
        )
        val peer = peerFromContact(contact)
        assertEquals(com.mengpaw.kernel.ports.Ports.ACP, peer.port)
    }
}
