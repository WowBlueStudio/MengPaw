// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import com.mengpaw.kernel.session.SessionEventBus
import com.mengpaw.shell.ui.screens.model.ChatMessageUi

/**
 * 保活判定策略 — 纯函数集 (零 Android 依赖, JVM 单测直跑)。
 *
 * 背景: 旧实现 ShellService 在 onCreate 无条件持 1 小时 PARTIAL_WAKE_LOCK,
 * 到期后无人续租 → 既"空转耗电"又"长任务 >1h 失去 CPU 保障"。
 * 现把"是否该持锁 / 是否要续租 / 是否重布看门狗 / 通知文案"全部抽成
 * 以显式时间参数驱动的纯判定, 设备侧只负责搬运状态 (见 [KeepAliveController])。
 *
 * 时间基准统一用 elapsedRealtime (单调时钟), 与 WakeLock 超时、AlarmManager
 * ELAPSED_REALTIME_WAKEUP 同源; 单测不依赖墙钟, 全部显式传入。
 */
object KeepAlivePolicy {

    // ── 参数取值 (理由见各常量注释) ──

    /**
     * 任务结束后的空闲宽限期: 超过即释放 WakeLock。
     * 取值 5 分钟 — 覆盖任务收尾 (最后一步落盘 / 检查点写入 / 会话持久化) 与
     * 用户连续追问的间隔; 相比旧实现固定 1 小时, 空闲耗电降到 1/12。
     */
    const val IDLE_RELEASE_MS = 5 * 60 * 1000L

    /** 单次 WakeLock 租期上限 — 到期必须重新判定, 绝不无限持锁到电池耗尽。 */
    const val WAKELOCK_DURATION_MS = 30 * 60 * 1000L

    /** 剩余寿命低于此值即续租: 30 分钟租期 - 15 分钟阈值 = 15 分钟安全余量。 */
    const val RENEW_THRESHOLD_MS = 15 * 60 * 1000L

    /**
     * Doze/厂商省电看门狗间隔: 10 分钟。
     * 与既有 TriggerEngine 系统唤醒同频 (registerSystemWake(…, 10)), 不额外增加唤醒次数;
     * 且 10 < 15 (续租阈值) 保证任何一次看门狗都能在租约到期前补租。
     */
    const val WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L

    /** 前台通知进度刷新间隔 (仅任务活跃期间有 ticker) — 30 秒足够体现步骤/耗时。 */
    const val NOTIFICATION_REFRESH_MS = 30 * 1000L

    /** 前台通知标题: 任务运行中。 */
    const val TITLE_RUNNING = "MengPaw 正在执行任务"

    /** 前台通知标题: 空闲待命 (与旧版一致)。 */
    const val TITLE_IDLE = "MengPaw 智能助手"

    /** 前台通知文案: 空闲已释放唤醒锁 (与旧版一致)。 */
    const val TEXT_IDLE = "后台运行中，智能体随时响应"

    /** 前台通知 / 保活渲染结果 — 纯数据, 便于单测断言。 */
    data class NotificationText(val title: String, val text: String)

    /** 会话事件 → 保活意图。 */
    enum class KeepAliveSignal { ACTIVE, IDLE, IGNORE }

    /**
     * 当前是否应持有 WakeLock。
     *
     * 判定规则 (纯时间比较, 无副作用):
     * - 任务活跃 → 必须持锁 (长任务期间保证 CPU 唤醒);
     * - 任务已结束但仍在空闲宽限期内 → 继续持锁 (收尾 + 连续追问);
     * - 超过宽限期 (含从未有过活跃记录 lastActiveAt=0) → 释放, 不空转耗电。
     */
    fun shouldHoldWakeLock(taskActive: Boolean, lastActiveAt: Long, now: Long): Boolean {
        if (taskActive) return true
        if (lastActiveAt <= 0L) return false
        return now - lastActiveAt < IDLE_RELEASE_MS
    }

    /** 是否需要续租/补租: 已到期或剩余寿命不足 [RENEW_THRESHOLD_MS] → true。 */
    fun shouldRenewWakeLock(expiresAt: Long, now: Long): Boolean {
        if (expiresAt <= 0L) return true
        if (expiresAt <= now) return true
        return expiresAt - now < RENEW_THRESHOLD_MS
    }

    /**
     * 看门狗是否需要重新布防 (= 是否还需要保活)。
     * 不需要保活时不布防, 避免"空闲仍每 10 分钟唤醒一次"的长期空转。
     */
    fun shouldRearmWatchdog(taskActive: Boolean, lastActiveAt: Long, now: Long): Boolean =
        shouldHoldWakeLock(taskActive, lastActiveAt, now)

    /** 空闲宽限期的释放时刻 (供通知显示剩余时间)。 */
    fun idleReleaseAt(lastActiveAt: Long): Long =
        if (lastActiveAt <= 0L) 0L else lastActiveAt + IDLE_RELEASE_MS

    /** 会话事件映射: 工具执行 = 任务活跃; 运行结束三类事件 = 任务结束; 其余不关心。 */
    fun signalOf(kind: SessionEventBus.EventKind): KeepAliveSignal = when (kind) {
        SessionEventBus.EventKind.TOOL_EXECUTED -> KeepAliveSignal.ACTIVE
        SessionEventBus.EventKind.RUN_COMPLETED,
        SessionEventBus.EventKind.RUN_FAILED,
        SessionEventBus.EventKind.RUN_INTERRUPTED -> KeepAliveSignal.IDLE
        else -> KeepAliveSignal.IGNORE
    }

    /** 毫秒 → 中文短耗时 ("45 秒" / "3 分 05 秒" / "1 小时 02 分")。 */
    fun formatDuration(ms: Long): String {
        val totalSec = (if (ms < 0L) 0L else ms) / 1000L
        return when {
            totalSec < 60L -> "$totalSec 秒"
            totalSec < 3600L -> "${totalSec / 60L} 分 ${(totalSec % 60L).toString().padStart(2, '0')} 秒"
            else -> "${totalSec / 3600L} 小时 ${((totalSec % 3600L) / 60L).toString().padStart(2, '0')} 分"
        }
    }

    /**
     * 前台通知渲染 (步骤 / 耗时 / 待执行队列 / 空闲剩余保活)。
     *
     * 三态: 运行中 → 空闲宽限 (仍持锁) → 空闲已释放 (降级为旧版常驻文案)。
     */
    fun renderNotification(
        taskActive: Boolean,
        elapsedMs: Long,
        stepCount: Int,
        pendingCount: Int,
        holdWakeLock: Boolean,
        idleRemainingMs: Long
    ): NotificationText {
        if (taskActive) {
            val parts = mutableListOf<String>()
            if (stepCount > 0) parts += "第 $stepCount 步"
            parts += "已运行 ${formatDuration(elapsedMs)}"
            if (pendingCount > 0) parts += "队列 $pendingCount 项"
            return NotificationText(TITLE_RUNNING, parts.joinToString(" · "))
        }
        if (holdWakeLock) {
            val remain = if (idleRemainingMs < 0L) 0L else idleRemainingMs
            return NotificationText(TITLE_IDLE, "任务已结束 · 空闲保活剩余 ${formatDuration(remain)}")
        }
        return NotificationText(TITLE_IDLE, TEXT_IDLE)
    }

    /**
     * 当前任务的步骤数 — 从消息末尾往前找**最后一条任务容器**
     * (ThinkingProcess, 兼容旧版 AgentWithTrace) 并取其步骤数; 找不到返回 0。
     * 只读不写, 供通知显示"第 N 步"。
     */
    fun currentStepCount(messages: List<ChatMessageUi>): Int {
        for (i in messages.size - 1 downTo 0) {
            when (val msg = messages[i]) {
                is ChatMessageUi.ThinkingProcess -> return msg.steps.size
                is ChatMessageUi.AgentWithTrace -> return msg.traces.size
                else -> Unit
            }
        }
        return 0
    }
}
