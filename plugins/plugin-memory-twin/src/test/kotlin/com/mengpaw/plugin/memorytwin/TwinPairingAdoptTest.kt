// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpCrypto
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.security.PromptFirewall
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 配对身份口径回归 (P0-B)。
 *
 * 背景: 5 连击发起方用**通讯录联系人名**建会话, 对端 PAIR_CHALLENGE 回带的是**设备指纹**,
 * 旧实现按指纹查会话 → 查不到 → 发起方永远拿不到验证码, 且没有任何错误提示。
 * 本测试锁住: 名称建会话 + 指纹回挑战 → 能接上; 确认后信任落在真实 ID 上, 同时保留名字别名。
 */
class TwinPairingAdoptTest {

    private lateinit var tempBase: File

    private class FakeTransport : AcpTransport {
        val sent = mutableListOf<AcpMessage>()
        override suspend fun send(message: AcpMessage): Boolean { sent.add(message); return true }
        override suspend fun sendForResult(message: AcpMessage, toPeerId: String, timeoutMs: Long): AcpResult? = null
        override suspend fun receive(): AcpMessage? = null
        override fun isConnected(): Boolean = true
        override fun close() {}
    }

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-pair-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    @Test
    fun `联系人名建会话 指纹回挑战也能接上并双侧落信任`() {
        val fake = FakeTransport()
        val started = TwinPairingEngine.initiatePairing(
            peerId = "台式机",            // 通讯录显示名
            myDeviceId = "fpA",
            myFingerprint = "fpA",
            capabilityCard = "{}",
            transport = fake
        )
        assertTrue("发起配对不应报错: ${started.error}", started.error.isEmpty())
        assertTrue("配对发起必须发出 ANNOUNCE", fake.sent.isNotEmpty())

        // 对端回挑战: 它的真实 ID 是设备指纹, 与联系人名不同
        val challenge = TwinPairingEngine.handleChallenge("fpB", "nonceB", "fpB")
        assertTrue("挑战必须被接上(旧实现报未找到会话): ${challenge.error}", challenge.error.isEmpty())
        assertEquals(TwinPairingEngine.PairingPhase.AWAITING_CONFIRM, challenge.phase)
        assertEquals(6, challenge.verificationCode.length)

        val confirmed = TwinPairingEngine.confirmPairing(challenge.sessionId)
        assertEquals(TwinPairingEngine.PairingPhase.ESTABLISHED, confirmed.phase)

        // 信任必须落在真实设备 ID 上 (否则反方向同步 403), 同时保留名字别名 (命令层按名字查)
        assertTrue("真实 ID 必须可信", PromptFirewall.isTrusted("fpB"))
        assertTrue("联系人名别名必须可信", PromptFirewall.isTrusted("台式机"))
        // 加密密钥按真实 ID 派生
        assertTrue(AcpCrypto.supportsEncryption("fpB"))
    }
}
