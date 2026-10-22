// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACP 敏感消息的 IP 绑定判定 (P0 回归)。
 *
 * 背景: 旧实现先 `bindPeerIp` 再 `isPeerFromBoundIp` —— 判定恒为 true,
 * "已配对才能读工作区"这条承诺名不副实。本测试锁住"先校验后绑定"的顺序语义。
 */
class AcpIpBindingTest {

    private fun server() = AcpServer(
        profile = AgentProfile(agentId = "agent", agentName = "agent"),
        sharedSecret = "test-secret"
    )

    @Test
    fun `敏感消息来自未绑定 IP 时拒绝 且拒绝本身不建立绑定`() {
        val s = server()
        assertFalse(authorizeAndBind(s, "WS_MANIFEST", "peer-1", "1.1.1.1"))
        // 关键: 被拒绝的请求不得"顺便"把 IP 记进白名单, 否则第二次就自动通过了
        assertFalse(s.isPeerFromBoundIp("peer-1", "1.1.1.1"))
    }

    @Test
    fun `非敏感消息建立绑定后 同一 IP 的敏感消息放行`() {
        val s = server()
        assertTrue(authorizeAndBind(s, "HEARTBEAT", "peer-1", "1.1.1.1"))
        assertTrue(authorizeAndBind(s, "WS_MANIFEST", "peer-1", "1.1.1.1"))
    }

    @Test
    fun `换一个来源 IP 的敏感消息被拒绝`() {
        val s = server()
        assertTrue(authorizeAndBind(s, "HEARTBEAT", "peer-1", "1.1.1.1"))
        assertFalse(authorizeAndBind(s, "WS_MANIFEST", "peer-1", "2.2.2.2"))
    }

    @Test
    fun `非敏感消息不要求已绑定`() {
        val s = server()
        assertTrue(authorizeAndBind(s, "CAPABILITY_ANNOUNCE", "peer-2", "3.3.3.3"))
    }
}
