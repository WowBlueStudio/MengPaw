// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.session.ResumePlan
import com.mengpaw.shell.ui.screens.model.AgentSession
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续跑「provider 就绪闩」顺序无关性测试 (P0, v0.48.x)。
 *
 * 背景: 恢复链已从 UI 首帧线程下沉 IO, 于是 `pendingResume` 的装载 (恢复链) 与
 * `markProviderReady()` (applyConfiguration) 的先后顺序不再确定。无论谁先, 都必须
 * **最终恰好触发一次** — 多触发 = 重复续跑同一任务, 漏触发 = 断点续跑静默失效。
 *
 * 断言口径是**回调计数** (禁止墙钟断言): 两种顺序各跑一遍, 期望计数恒为 1。
 * 本类只驱动内存态 (闩 + 计划 + 计数), 不触盘, 故不需要 Robolectric。
 */
class SessionResumeGateTest {

    /** 最小可构造宿主 — 恢复链测试里 sessions 恒空, 不读盘。 */
    private fun newService(): SessionPersistenceService = SessionPersistenceService(
        sessions = mutableMapOf<String, AgentSession>(),
        viewModelScope = TestScope(),
        getActiveAgentName = { "MengPaw" },
        onSwitchAgent = {},
        onStopAgent = {},
        onCreateAgent = { _, _ -> }
    )

    /** 构造一条待续跑计划 (step/lastStep 语义与内核 ResumePlanner 输出一致)。 */
    private fun plan(sessionId: String) = ResumePlan.AutoResume(
        sessionId = sessionId,
        task = "继续未完成的任务",
        step = 3,
        lastStep = 5
    )

    @Test
    fun `先装载计划后标记就绪_恰好触发一次`() {
        val service = newService()
        var fired = 0
        service.onResumeRequested = { fired++; true }

        // 顺序 A: 恢复链先跑完 (计划已装载), provider 之后才就绪
        service.pendingResume = plan("sess_a")
        assertFalse("闩未置位时不得消费计划", service.firePendingResume())
        assertEquals("闩未置位不得触发", 0, fired)

        assertTrue("闩置位后必须触发", service.markProviderReady())
        assertEquals("恰好一次", 1, fired)

        // 恢复链末端的补触发 (幂等) + 重复 applyConfiguration 都不得再触发
        assertFalse(service.firePendingResume())
        assertFalse(service.markProviderReady())
        assertEquals("重复调用不得再触发", 1, fired)
    }

    @Test
    fun `先标记就绪后装载计划_恢复链末端恰好触发一次`() {
        val service = newService()
        var fired = 0
        service.onResumeRequested = { fired++; true }

        // 顺序 B: applyConfiguration 先到 (provider 就绪时计划还没读出来)
        assertFalse("尚无可触发计划", service.markProviderReady())
        assertEquals(0, fired)

        // 恢复链随后完成装载, 末端补触发
        service.pendingResume = plan("sess_b")
        assertTrue(service.firePendingResume())
        assertEquals("恰好一次", 1, fired)

        assertFalse("再触发不得重复", service.firePendingResume())
        assertEquals(1, fired)
    }

    @Test
    fun `同一会话重复装载_去重集合拦住第二次`() {
        val service = newService()
        var fired = 0
        service.onResumeRequested = { fired++; true }

        service.markProviderReady()
        service.pendingResume = plan("sess_c")
        assertTrue(service.firePendingResume())
        // 同一会话被再次装载 (例如切换 Agent 后重建) → autoResumedSessions 拦住
        service.pendingResume = plan("sess_c")
        assertFalse(service.firePendingResume())
        assertEquals(1, fired)
    }

    @Test
    fun `无执行器时不消费计划_待装配后仍可触发`() {
        val service = newService()
        service.markProviderReady()
        service.pendingResume = plan("sess_d")

        // 执行器缺失 (极端启动窗口) → 计划必须留着, 否则这次续跑被静默吞掉
        assertFalse(service.firePendingResume())
        assertNotNull("计划不得被消费", service.pendingResume)

        var fired = 0
        service.onResumeRequested = { fired++; true }
        assertTrue(service.firePendingResume())
        assertEquals(1, fired)
    }
}
