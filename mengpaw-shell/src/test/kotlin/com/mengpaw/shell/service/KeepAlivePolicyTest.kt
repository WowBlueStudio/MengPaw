// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import com.mengpaw.kernel.session.SessionEventBus
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeepAlivePolicy] 单测 — 保活边界判定 (纯函数, 显式时间参数, 不依赖墙钟/Android)。
 *
 * 锁死三类边界:
 * 1. "当前是否应持有 WakeLock" (任务活跃 / 空闲宽限内 / 空闲超时);
 * 2. "是否需要续租" (剩余寿命 vs 阈值, 含到期与从未取得);
 * 3. "是否需要重新布防看门狗" + 参数不变量 (租期 > 看门狗间隔, 且间隔 < 续租阈值)。
 * 另覆盖通知三态渲染、耗时格式化、步骤计数、会话事件映射。
 */
class KeepAlivePolicyTest {

    /** 显式"当前时刻" (elapsedRealtime 口径), 所有断言均以此为准。 */
    private val now = 10_000_000L

    // ── ① 是否应持有 WakeLock ──

    @Test
    fun 任务活跃时必持锁即使超过空闲宽限() {
        assertTrue(
            KeepAlivePolicy.shouldHoldWakeLock(
                taskActive = true,
                lastActiveAt = now - KeepAlivePolicy.IDLE_RELEASE_MS * 3,
                now = now
            )
        )
    }

    @Test
    fun 任务结束但空闲宽限内仍持锁() {
        assertTrue(
            KeepAlivePolicy.shouldHoldWakeLock(
                taskActive = false,
                lastActiveAt = now - (KeepAlivePolicy.IDLE_RELEASE_MS / 2),
                now = now
            )
        )
    }

    @Test
    fun 空闲恰好到达宽限期即释放() {
        assertFalse(
            KeepAlivePolicy.shouldHoldWakeLock(
                taskActive = false,
                lastActiveAt = now - KeepAlivePolicy.IDLE_RELEASE_MS,
                now = now
            )
        )
    }

    @Test
    fun 空闲差一毫秒仍持锁() {
        assertTrue(
            KeepAlivePolicy.shouldHoldWakeLock(
                taskActive = false,
                lastActiveAt = now - KeepAlivePolicy.IDLE_RELEASE_MS + 1,
                now = now
            )
        )
    }

    @Test
    fun 从未活跃过的冷启动不持锁() {
        assertFalse(
            KeepAlivePolicy.shouldHoldWakeLock(taskActive = false, lastActiveAt = 0L, now = now)
        )
    }

    @Test
    fun 空闲释放时刻等于活跃时刻加宽限() {
        assertEquals(
            now + KeepAlivePolicy.IDLE_RELEASE_MS,
            KeepAlivePolicy.idleReleaseAt(now)
        )
        assertEquals(0L, KeepAlivePolicy.idleReleaseAt(0L))
    }

    // ── ② 是否需要续租 ──

    @Test
    fun 剩余寿命低于阈值需要续租() {
        assertTrue(
            KeepAlivePolicy.shouldRenewWakeLock(
                expiresAt = now + KeepAlivePolicy.RENEW_THRESHOLD_MS - 1,
                now = now
            )
        )
    }

    @Test
    fun 剩余寿命恰等于阈值不续租() {
        assertFalse(
            KeepAlivePolicy.shouldRenewWakeLock(
                expiresAt = now + KeepAlivePolicy.RENEW_THRESHOLD_MS,
                now = now
            )
        )
    }

    @Test
    fun 锁已到期或从未取得都需要补租() {
        assertTrue(KeepAlivePolicy.shouldRenewWakeLock(expiresAt = now, now = now))
        assertTrue(KeepAlivePolicy.shouldRenewWakeLock(expiresAt = now - 1, now = now))
        assertTrue(KeepAlivePolicy.shouldRenewWakeLock(expiresAt = 0L, now = now))
    }

    @Test
    fun 租期与看门狗间隔保证到期前必然续租() {
        // 不变量: 一次看门狗周期后剩余寿命仍高于续租阈值 (否则会出现"刚补租又到期")
        assertTrue(
            KeepAlivePolicy.WAKELOCK_DURATION_MS - KeepAlivePolicy.WATCHDOG_INTERVAL_MS >
                KeepAlivePolicy.RENEW_THRESHOLD_MS
        )
        // 且看门狗间隔小于续租阈值 → 任何一次唤醒都足以触发续租
        assertTrue(KeepAlivePolicy.WATCHDOG_INTERVAL_MS < KeepAlivePolicy.RENEW_THRESHOLD_MS)
    }

    // ── ③ 看门狗是否重新布防 ──

    @Test
    fun 任务活跃时看门狗需布防() {
        assertTrue(
            KeepAlivePolicy.shouldRearmWatchdog(
                taskActive = true, lastActiveAt = now - 60 * 60 * 1000L, now = now
            )
        )
    }

    @Test
    fun 空闲超时后看门狗不布防避免空转唤醒() {
        assertFalse(
            KeepAlivePolicy.shouldRearmWatchdog(
                taskActive = false,
                lastActiveAt = now - KeepAlivePolicy.IDLE_RELEASE_MS - 1,
                now = now
            )
        )
    }

    // ── ④ 通知三态渲染 ──

    @Test
    fun 运行态通知显示步骤耗时与队列() {
        val text = KeepAlivePolicy.renderNotification(
            taskActive = true, elapsedMs = 125_000L, stepCount = 3, pendingCount = 2,
            holdWakeLock = true, idleRemainingMs = 0L
        )
        assertEquals(KeepAlivePolicy.TITLE_RUNNING, text.title)
        assertTrue(text.text.contains("第 3 步"))
        assertTrue(text.text.contains("已运行 2 分 05 秒"))
        assertTrue(text.text.contains("队列 2 项"))
    }

    @Test
    fun 运行态无步骤时不显示步骤段() {
        val text = KeepAlivePolicy.renderNotification(
            taskActive = true, elapsedMs = 5_000L, stepCount = 0, pendingCount = 0,
            holdWakeLock = true, idleRemainingMs = 0L
        )
        assertEquals("已运行 5 秒", text.text)
    }

    @Test
    fun 空闲宽限态通知显示剩余保活时间() {
        val text = KeepAlivePolicy.renderNotification(
            taskActive = false, elapsedMs = 0L, stepCount = 0, pendingCount = 0,
            holdWakeLock = true, idleRemainingMs = 60_000L
        )
        assertEquals(KeepAlivePolicy.TITLE_IDLE, text.title)
        assertTrue(text.text.contains("空闲保活剩余 1 分 00 秒"))
    }

    @Test
    fun 空闲释放态降级为常驻文案() {
        val text = KeepAlivePolicy.renderNotification(
            taskActive = false, elapsedMs = 0L, stepCount = 0, pendingCount = 0,
            holdWakeLock = false, idleRemainingMs = 0L
        )
        assertEquals(KeepAlivePolicy.NotificationText(KeepAlivePolicy.TITLE_IDLE, KeepAlivePolicy.TEXT_IDLE), text)
    }

    @Test
    fun 耗时格式化分段正确() {
        assertEquals("0 秒", KeepAlivePolicy.formatDuration(0L))
        assertEquals("45 秒", KeepAlivePolicy.formatDuration(45_900L))
        assertEquals("3 分 05 秒", KeepAlivePolicy.formatDuration(185_000L))
        assertEquals("1 小时 02 分", KeepAlivePolicy.formatDuration(3_725_000L))
        assertEquals("0 秒", KeepAlivePolicy.formatDuration(-5L))
    }

    // ── ⑤ 步骤计数 ──

    @Test
    fun 步骤数取最近的任务容器() {
        val older = ChatMessageUi.ThinkingProcess(
            steps = listOf(ChatMessageUi.ProcessStep(), ChatMessageUi.ProcessStep())
        )
        val latest = ChatMessageUi.ThinkingProcess(
            steps = listOf(
                ChatMessageUi.ProcessStep(),
                ChatMessageUi.ProcessStep(),
                ChatMessageUi.ProcessStep()
            )
        )
        assertEquals(3, KeepAlivePolicy.currentStepCount(listOf(older, ChatMessageUi.System("x"), latest)))
        assertEquals(0, KeepAlivePolicy.currentStepCount(emptyList()))
    }

    @Test
    fun 步骤数兼容旧版轨迹消息() {
        val legacy = ChatMessageUi.AgentWithTrace(
            finalContent = "",
            traces = listOf(
                com.mengpaw.shell.ui.screens.model.AgentTrace(1, "t", null, null)
            )
        )
        assertEquals(1, KeepAlivePolicy.currentStepCount(listOf(legacy)))
    }

    // ── ⑥ 会话事件映射 ──

    @Test
    fun 工具执行事件视为任务活跃() {
        assertEquals(
            KeepAlivePolicy.KeepAliveSignal.ACTIVE,
            KeepAlivePolicy.signalOf(SessionEventBus.EventKind.TOOL_EXECUTED)
        )
    }

    @Test
    fun 三类运行结束事件都视为任务结束() {
        assertEquals(
            KeepAlivePolicy.KeepAliveSignal.IDLE,
            KeepAlivePolicy.signalOf(SessionEventBus.EventKind.RUN_COMPLETED)
        )
        assertEquals(
            KeepAlivePolicy.KeepAliveSignal.IDLE,
            KeepAlivePolicy.signalOf(SessionEventBus.EventKind.RUN_FAILED)
        )
        assertEquals(
            KeepAlivePolicy.KeepAliveSignal.IDLE,
            KeepAlivePolicy.signalOf(SessionEventBus.EventKind.RUN_INTERRUPTED)
        )
    }

    @Test
    fun 无关事件不改变保活状态() {
        assertEquals(
            KeepAlivePolicy.KeepAliveSignal.IGNORE,
            KeepAlivePolicy.signalOf(SessionEventBus.EventKind.LLM_CALL_COMPLETED)
        )
        assertEquals(
            KeepAlivePolicy.KeepAliveSignal.IGNORE,
            KeepAlivePolicy.signalOf(SessionEventBus.EventKind.SESSION_CREATED)
        )
    }
}
