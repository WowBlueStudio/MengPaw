// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.mengpaw.kernel.session.SessionEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 任务级保活控制器 (v0.47.x) — 前台服务 + WakeLock 的生命周期主人。
 *
 * 旧实现的两个缺陷 (本次修复):
 * 1. `ShellService.onCreate` 无条件持 1 小时 WakeLock, 无人管任务是否在跑 → 空转耗电;
 * 2. 1 小时到期后没有任何续租路径 → 长任务失去 CPU 唤醒保障。
 *
 * 现改为**由任务状态驱动的租约模型**:
 * - 任务开始/活跃 (UI isRunning 或内核会话事件) → 补租 WakeLock ([KeepAlivePolicy.WAKELOCK_DURATION_MS]);
 * - 每 [KeepAlivePolicy.NOTIFICATION_REFRESH_MS] 一次主线程 ticker: 刷新通知进度 + 续租判定;
 * - 任务结束 → 进入 [KeepAlivePolicy.IDLE_RELEASE_MS] 空闲宽限, 到期释放 WakeLock 并降级通知;
 * - 每 [KeepAlivePolicy.WATCHDOG_INTERVAL_MS] 一次 AlarmManager 精确闹钟 (Doze 可穿透) 兜底:
 *   唤醒后重新判定, 需要保活才补租并重布看门狗, 不需要就撤销 (不做长期空转唤醒)。
 *
 * 对外 API 全部为静态函数, 可从任意层调用 (不依赖 UI 类):
 * [attach] / [markUserActive] / [onTaskActive] / [updateProgress] / [onTaskIdle] /
 * [onWatchdog] / [detach]。判定逻辑在 [KeepAlivePolicy] (纯函数, 有单测)。
 *
 * 线程安全: 状态变更方法均 @Synchronized (主线程 ticker / 服务生命周期 / IO 协程可并发进入)。
 */
object KeepAliveController {

    private const val TAG = "KeepAlive"

    /** 看门狗广播 action — WakeReceiver 按此路由 (显式组件 Intent, 不依赖隐式广播)。 */
    const val ACTION_KEEPALIVE_WATCHDOG = "com.mengpaw.action.KEEPALIVE_WATCHDOG"

    /** 看门狗 PendingIntent requestCode — 与 kernel CronAlarmScheduler 的 0/1001 错开。 */
    private const val WATCHDOG_REQUEST_CODE = 2002

    /** WakeLock tag — 沿用旧值, 便于 `dumpsys power` 前后对照。 */
    private const val WAKELOCK_TAG = "mengpaw:shell-service"

    @Volatile private var appContext: Context? = null
    @Volatile private var taskActive = false

    /** 最近一次活跃时刻 (elapsedRealtime); 0 = 从未活跃 → 不持锁。 */
    @Volatile private var lastActiveAt = 0L
    @Volatile private var taskStartedAt = 0L
    @Volatile private var stepCount = 0
    @Volatile private var pendingCount = 0

    private var wakeLock: PowerManager.WakeLock? = null
    private var lockExpiresAt = 0L
    private var watchdogArmed = false
    private var tickerRunning = false
    private var bridgeScope: CoroutineScope? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tickAction = Runnable { tickOnce() }

    /** 单调时钟 — 与 WakeLock 超时 / ELAPSED_REALTIME_WAKEUP 同源。 */
    fun now(): Long = SystemClock.elapsedRealtime()

    // ── 服务生命周期入口 ──

    /**
     * 服务创建/再次被启动时接入控制器 (幂等)。
     * **冷启动不预持锁** — 无任务且无活跃记录时零耗电; 用户打开 App 由 [markUserActive] 给宽限。
     */
    @Synchronized
    fun attach(context: Context) {
        appContext = context.applicationContext
        startBridge()
        val hold = evaluate(now())
        if (hold) ensureTicker() else stopTicker()
    }

    /** 用户在前台 (打开 App/回到前台) — 给一个空闲宽限窗口, 覆盖"即将下发长任务"。 */
    @Synchronized
    fun markUserActive(context: Context) {
        appContext = context.applicationContext
        startBridge()
        val now = now()
        lastActiveAt = now
        applyState(now)
    }

    /** 任务开始 / 任务仍活跃 (UI isRunning=true 或 TOOL_EXECUTED)。 */
    @Synchronized
    fun onTaskActive(context: Context) {
        appContext = context.applicationContext
        startBridge()
        // 任务开始 → 再确保前台服务存活 (幂等; 服务被厂商省电杀掉后自愈)。
        // ShellService.start 内部已捕获后台启动限制异常, 不会抛给调用方。
        try { ShellService.start(context.applicationContext) } catch (_: Exception) {}
        markActive(now())
    }

    /** 任务结束 (UI isRunning=false 或 RUN_COMPLETED/RUN_FAILED/RUN_INTERRUPTED)。 */
    @Synchronized
    fun onTaskIdle(context: Context) {
        appContext = context.applicationContext
        startBridge()
        markIdle(now())
    }

    /** 进度上报: 步骤数 / 待执行队列数。只更新内存, 由 ticker 用节流后的频率刷新通知。 */
    fun updateProgress(steps: Int, pending: Int) {
        stepCount = if (steps < 0) 0 else steps
        pendingCount = if (pending < 0) 0 else pending
    }

    /**
     * 看门狗唤醒 (AlarmManager 精确闹钟 → WakeReceiver)。
     *
     * [pendingWork] = 唤醒时检测到有未完成事项 (inbox 待办):
     * 有则把宽限窗口起点推到当前时刻 (补租 WakeLock 让 Agent 有 CPU 拉起任务);
     * 无且空闲已超时 → 释放并撤销看门狗, 不再周期唤醒。
     */
    @Synchronized
    fun onWatchdog(context: Context, pendingWork: Boolean) {
        appContext = context.applicationContext
        startBridge()
        val now = now()
        if (pendingWork && !taskActive) lastActiveAt = now
        watchdogArmed = false // 本次闹钟已消费, 允许重新布防
        val hold = evaluate(now)
        if (hold) ensureTicker() else stopTicker()
    }

    /** 服务销毁: 释放 WakeLock / 撤销看门狗 / 停 ticker; 保留任务状态供服务重启后恢复。 */
    @Synchronized
    fun detach(context: Context) {
        releaseLock()
        cancelWatchdog(context.applicationContext)
        stopTicker()
        stopBridge()
        appContext = null
    }

    // ── 内部状态机 ──

    private fun markActive(now: Long) {
        if (!taskActive) {
            taskActive = true
            taskStartedAt = now
            stepCount = 0
        }
        lastActiveAt = now
        applyState(now)
    }

    private fun markIdle(now: Long) {
        taskActive = false
        taskStartedAt = 0L
        stepCount = 0
        lastActiveAt = now // 空闲宽限起点
        applyState(now)
    }

    private fun applyState(now: Long) {
        val hold = evaluate(now)
        if (hold) ensureTicker() else stopTicker()
    }

    /** 核心判定: 该持锁就补租+布防, 不该持锁就释放+撤防, 然后刷新通知。 */
    private fun evaluate(now: Long): Boolean {
        val ctx = appContext ?: return false
        val hold = KeepAlivePolicy.shouldHoldWakeLock(taskActive, lastActiveAt, now)
        if (hold) acquireOrRenew(now) else releaseLock()
        if (KeepAlivePolicy.shouldRearmWatchdog(taskActive, lastActiveAt, now)) {
            if (!watchdogArmed) armWatchdog(ctx) // 已布防则不重复推迟, 保证 10 分钟真会响
        } else {
            cancelWatchdog(ctx)
        }
        updateNotification(ctx, now, hold)
        return hold
    }

    @Synchronized
    private fun tickOnce() {
        val ctx = appContext ?: return
        try {
            val hold = evaluate(now())
            if (hold) ensureTicker() else stopTicker()
        } catch (e: Exception) {
            Log.w(TAG, "保活 tick 失败: ${e.message}")
            stopTicker()
        }
    }

    // ── WakeLock 租约 ──

    private fun acquireOrRenew(now: Long) {
        try {
            val lock = wakeLock ?: createLock() ?: return
            if (lock.isHeld && !KeepAlivePolicy.shouldRenewWakeLock(lockExpiresAt, now)) return
            if (lock.isHeld) lock.release() // 先释放再取, 明确重置超时 (非引用计数锁)
            lock.acquire(KeepAlivePolicy.WAKELOCK_DURATION_MS)
            lockExpiresAt = now + KeepAlivePolicy.WAKELOCK_DURATION_MS
            Log.d(TAG, "WakeLock 已续租 ${KeepAlivePolicy.WAKELOCK_DURATION_MS}ms")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock 获取/续租失败: ${e.message}")
        }
    }

    private fun createLock(): PowerManager.WakeLock? {
        val ctx = appContext ?: return null
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG) ?: return null
        lock.setReferenceCounted(false)
        wakeLock = lock
        return lock
    }

    private fun releaseLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock 释放失败: ${e.message}")
        }
        lockExpiresAt = 0L
    }

    // ── Doze / 厂商省电看门狗 ──

    private fun armWatchdog(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = watchdogIntent(ctx) ?: return
            val triggerAt = SystemClock.elapsedRealtime() + KeepAlivePolicy.WATCHDOG_INTERVAL_MS
            var armed = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    armed = true
                } catch (e: Exception) {
                    Log.w(TAG, "精确闹钟不可用, 回退非精确: ${e.message}")
                }
            }
            if (!armed) {
                // 无精确闹钟权限时回退: setAndAllowWhileIdle 免权限且可穿透 Doze (时间精度放宽)
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            watchdogArmed = true
            Log.d(TAG, "保活看门狗已布防 ${KeepAlivePolicy.WATCHDOG_INTERVAL_MS}ms 后")
        } catch (e: Exception) {
            watchdogArmed = false
            Log.w(TAG, "看门狗布防失败: ${e.message}")
        }
    }

    private fun cancelWatchdog(ctx: Context) {
        if (!watchdogArmed) return
        watchdogArmed = false
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val pi = watchdogIntent(ctx)
            if (pi != null) {
                am?.cancel(pi)
                pi.cancel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "看门狗撤销失败: ${e.message}")
        }
    }

    private fun watchdogIntent(ctx: Context): PendingIntent? = try {
        PendingIntent.getBroadcast(
            ctx,
            WATCHDOG_REQUEST_CODE,
            Intent(ctx, WakeReceiver::class.java).setAction(ACTION_KEEPALIVE_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } catch (e: Exception) {
        Log.w(TAG, "看门狗 PendingIntent 创建失败: ${e.message}")
        null
    }

    // ── 通知 ──

    private fun updateNotification(ctx: Context, now: Long, hold: Boolean) {
        try {
            val elapsed = if (taskStartedAt > 0L) now - taskStartedAt else 0L
            val idleRemain = if (!taskActive && hold) {
                (KeepAlivePolicy.idleReleaseAt(lastActiveAt) - now).coerceAtLeast(0L)
            } else 0L
            val text = KeepAlivePolicy.renderNotification(
                taskActive = taskActive,
                elapsedMs = elapsed,
                stepCount = stepCount,
                pendingCount = pendingCount,
                holdWakeLock = hold,
                idleRemainingMs = idleRemain
            )
            ShellService.updateNotification(ctx, text.title, text.text)
        } catch (e: Exception) {
            Log.w(TAG, "前台通知刷新失败: ${e.message}")
        }
    }

    // ── ticker ──

    private fun ensureTicker() {
        if (tickerRunning) return
        tickerRunning = true
        ticker.postDelayed(tickAction, KeepAlivePolicy.NOTIFICATION_REFRESH_MS)
    }

    private fun stopTicker() {
        if (!tickerRunning) return
        tickerRunning = false
        try {
            ticker.removeCallbacks(tickAction)
        } catch (_: Exception) {
        }
    }

    // ── 内核会话事件桥 (与 UI 无关的兜底活跃信号) ──

    /**
     * 订阅 [SessionEventBus]: TOOL_EXECUTED = 活跃, RUN_COMPLETED/FAILED/INTERRUPTED = 结束。
     * 用途: 进程存活但 Activity 已被销毁 (或未接线) 时, 后台任务仍能获得保活;
     * 与 UI isRunning 通道互为兜底 (宽限期保证任何一方漏报都不会立刻掉锁)。
     */
    private fun startBridge() {
        if (bridgeScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        bridgeScope = scope
        scope.launch {
            try {
                SessionEventBus.events.collect { event: SessionEventBus.SessionEvent ->
                    when (KeepAlivePolicy.signalOf(event.kind)) {
                        KeepAlivePolicy.KeepAliveSignal.ACTIVE -> onBridgeActive()
                        KeepAlivePolicy.KeepAliveSignal.IDLE -> onBridgeIdle()
                        KeepAlivePolicy.KeepAliveSignal.IGNORE -> Unit
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "会话事件桥中断: ${e.message}")
            }
        }
    }

    private fun stopBridge() {
        try {
            bridgeScope?.cancel()
        } catch (_: Exception) {
        }
        bridgeScope = null
    }

    @Synchronized
    private fun onBridgeActive() {
        if (appContext == null) return
        markActive(now())
    }

    @Synchronized
    private fun onBridgeIdle() {
        if (appContext == null) return
        markIdle(now())
    }
}
