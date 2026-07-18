// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.trigger

import kotlinx.coroutines.*
import java.io.File
import java.util.Random

/**
 * Trigger Engine — 事件驱动的 Agent 主动行为引擎。
 *
 * 两种触发模式:
 * 1. Cron 定时 — 固定时间执行任务（如 "0 9 * * *" 每天9:00）
 * 2. Lifetime 真人感 — 在设定时间段内随机时间，Agent 主动发起话题
 *
 * 存储路径: Agent文档/triggers/
 */
object TriggerEngine {
    private val triggers = mutableListOf<Trigger>()
    private val random = Random()
    private var lifetimeJob: Job? = null

    // ── Types ──────────────────────────────────────────────────────

    data class Trigger(
        val id: String,
        val type: TriggerType,
        val config: String,        // cron expression or "HH:mm-HH:mm"
        val action: String,        // task description for Agent
        val enabled: Boolean = true,
        val lastFired: Long = 0
    )

    enum class TriggerType { CRON, LIFETIME }

        /** Callback when a trigger fires — Agent should execute the action. */
    var onFire: ((Trigger) -> Unit)? = null

    /** Callback when system wakes — Agent should check ACP inbox and execute pending tasks. */
    var onWake: (() -> Unit)? = null

    // ── Alarm-based System Wake ──────────────────────────────────

    /**
     * Register a system-level repeating wake alarm.
     * Survives Doze mode and app process death (via AlarmManager).
     * On wake, checks ACP inbox + fires Lifetime triggers.
     *
     * @param context Android Context (pass applicationContext)
     * @param intervalMinutes Wake interval (default 10min, min 5min for Doze compliance)
     */
    fun registerSystemWake(context: Any? /* android.content.Context */, intervalMinutes: Int = 10) {
        if (context == null) return
        try {
            val ctxClass = Class.forName("android.content.Context")
            val alarmClass = Class.forName("android.app.AlarmManager")
            val intentClass = Class.forName("android.content.Intent")
            val pendingIntentClass = Class.forName("android.app.PendingIntent")
            val broadcastReceiverClass = Class.forName("android.content.BroadcastReceiver")

            val alarmManager = ctxClass.getMethod("getSystemService", String::class.java)
                .invoke(context, "alarm")

            val intent = intentClass.getConstructor(ctxClass, Class.forName("java.lang.Class"))
                .newInstance(context, Class.forName("com.mengpaw.shell.service.WakeReceiver"))

            val pendingIntent = pendingIntentClass.getMethod("getBroadcast",
                ctxClass, Int::class.javaPrimitiveType, intentClass, Int::class.javaPrimitiveType)
                .invoke(null, context, 0, intent,
                    pendingIntentClass.getField("FLAG_IMMUTABLE").getInt(null) or
                    pendingIntentClass.getField("FLAG_UPDATE_CURRENT").getInt(null))

            val intervalMs = (intervalMinutes.coerceAtLeast(5) * 60 * 1000).toLong()
            alarmManager?.javaClass?.getMethod("setInexactRepeating",
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, pendingIntentClass)
                ?.invoke(alarmManager,
                    alarmClass.getField("ELAPSED_REALTIME_WAKEUP").getInt(null),
                    intervalMs, intervalMs, pendingIntent)

            android.util.Log.d("TriggerEngine", "System wake registered every ${intervalMinutes}min")
        } catch (e: Exception) {
            android.util.Log.w("TriggerEngine", "AlarmManager not available: ${e.message}")
        }
    }

    /** Called by WakeReceiver when AlarmManager fires. Don't block — return quickly. */
    fun onSystemWake() {
        val now = System.currentTimeMillis()
        // Check Cron triggers (exact wall-clock time matching)
        triggers.filter { it.enabled && it.type == TriggerType.CRON }.forEach { checkCron(it, now) }
        // Check Lifetime triggers (random window matching)
        triggers.filter { it.enabled && it.type == TriggerType.LIFETIME }.forEach { checkLifetime(it, now) }
        // Notify Agent to check ACP inbox
        onWake?.invoke()
    }

    /**
     * Register exact alarm for the next Cron trigger.
     * Unlike the repeating check alarm, this wakes at the exact Cron time.
     * Call after adding a new Cron trigger or on startup.
     */
    fun registerCronAlarm(context: Any? /* android.content.Context */) {
        if (context == null) { android.util.Log.d("TriggerEngine", "Cron alarm skipped: no Context"); return }
        try {
            val now = java.util.Calendar.getInstance()
            var nextTime = Long.MAX_VALUE

            triggers.filter { it.enabled && it.type == TriggerType.CRON }.forEach { trigger ->
                val parts = trigger.config.split(" ").take(5)
                if (parts.size < 5) return@forEach
                val target = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    if (parts[1] != "*") set(java.util.Calendar.HOUR_OF_DAY, parts[1].toIntOrNull() ?: get(java.util.Calendar.HOUR_OF_DAY))
                    if (parts[0] != "*") set(java.util.Calendar.MINUTE, parts[0].toIntOrNull() ?: get(java.util.Calendar.MINUTE))
                    // If the target time has passed today, schedule for tomorrow
                    if (timeInMillis <= now.timeInMillis && parts[1] != "*") add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                if (target.timeInMillis < nextTime) nextTime = target.timeInMillis
            }

            if (nextTime == Long.MAX_VALUE) return // No cron triggers

            val ctxClass = Class.forName("android.content.Context")
            val alarmClass = Class.forName("android.app.AlarmManager")
            val intentClass = Class.forName("android.content.Intent")
            val pendingIntentClass = Class.forName("android.app.PendingIntent")

            val alarmManager = ctxClass.getMethod("getSystemService", String::class.java).invoke(context, "alarm")
            val intent = intentClass.getConstructor(ctxClass, Class.forName("java.lang.Class"))
                .newInstance(context, Class.forName("com.mengpaw.shell.service.WakeReceiver"))
            intent.javaClass.getMethod("putExtra", String::class.java, String::class.java)
                .invoke(intent, "wake_reason", "cron")

            val pendingIntent = pendingIntentClass.getMethod("getBroadcast",
                ctxClass, Int::class.javaPrimitiveType, intentClass, Int::class.javaPrimitiveType)
                .invoke(null, context, 1001, intent,
                    pendingIntentClass.getField("FLAG_IMMUTABLE").getInt(null) or
                    pendingIntentClass.getField("FLAG_UPDATE_CURRENT").getInt(null))

            alarmManager?.javaClass?.getMethod("set",
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, pendingIntentClass)
                ?.invoke(alarmManager,
                    alarmClass.getField("RTC_WAKEUP").getInt(null), nextTime, pendingIntent)

            android.util.Log.d("TriggerEngine", "Next Cron wake: ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(nextTime))}")
        } catch (e: Exception) {
            android.util.Log.w("TriggerEngine", "Cron alarm registration failed: ${e.message}")
        }
    }

    // ── Management ─────────────────────────────────────────────────

    fun addCron(id: String, cronExpr: String, action: String) {
        triggers.add(Trigger(id, TriggerType.CRON, cronExpr, action))
    }

    fun addLifetime(id: String, timeRange: String, action: String) {
        triggers.add(Trigger(id, TriggerType.LIFETIME, timeRange, action))
    }

    fun remove(id: String) { triggers.removeAll { it.id == id } }
    fun enable(id: String) { triggers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { triggers[it] = triggers[it].copy(enabled = true) } }
    fun disable(id: String) { triggers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { triggers[it] = triggers[it].copy(enabled = false) } }
    fun list(): List<Trigger> = triggers.toList()
    fun clear() { triggers.clear(); lifetimeJob?.cancel() }

    // ── Scheduler ──────────────────────────────────────────────────

    /** Start the trigger loop. Call once at app startup. */
    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                var hasActiveJobs = false
                triggers.filter { it.enabled }.forEach { trigger ->
                    when (trigger.type) {
                        TriggerType.CRON -> { checkCron(trigger, now); hasActiveJobs = true }
                        TriggerType.LIFETIME -> { checkLifetime(trigger, now); hasActiveJobs = true }
                    }
                }
                // Adaptive polling: 30s when active triggers exist, 5min when idle (battery saving)
                delay(if (hasActiveJobs) 30_000L else 300_000L)
            }
        }
    }

    // ── Cron ──────────────────────────────────────────────────────

    private fun checkCron(t: Trigger, now: Long) {
        // Simple cron minute-level matching: "M H D M W" (5-field)
        val parts = t.config.split(" ").take(5)
        if (parts.size < 5) return
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val min = cal.get(java.util.Calendar.MINUTE)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1

        fun match(expr: String, actual: Int): Boolean =
            expr == "*" || expr.split(",").any { p ->
                p == actual.toString() || (p.contains("/") && actual % p.split("/")[1].toInt() == 0)
            }

        if (match(parts[0], min) && match(parts[1], hour) && match(parts[2], day) && match(parts[3], month) && match(parts[4], dow)) {
            val lastMinute = java.util.Calendar.getInstance().apply { timeInMillis = t.lastFired }.get(java.util.Calendar.MINUTE)
            if (min != lastMinute) fireTrigger(t)
        }
    }

    // ── Lifetime (真人感随机触发) ─────────────────────────────────

    private val lifetimeTimers = mutableMapOf<String, Long>() // id → nextFireTime

    private fun checkLifetime(t: Trigger, now: Long) {
        // Schedule next fire if not scheduled
        if (t.id !in lifetimeTimers || now >= (lifetimeTimers[t.id] ?: 0)) {
            fireTrigger(t)
            scheduleNextLifetime(t, now)
        }
    }

    private fun scheduleNextLifetime(t: Trigger, now: Long) {
        // Format: "HH:mm-HH:mm" e.g. "10:00-20:00"
        val range = t.config.split("-")
        if (range.size != 2) return
        val startParts = range[0].split(":"); val endParts = range[1].split(":")
        val startH = startParts[0].toIntOrNull() ?: 10; val startM = startParts.getOrNull(1)?.toIntOrNull() ?: 0
        val endH = endParts[0].toIntOrNull() ?: 20; val endM = endParts.getOrNull(1)?.toIntOrNull() ?: 0

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val todayStart = cal.apply { set(java.util.Calendar.HOUR_OF_DAY, startH); set(java.util.Calendar.MINUTE, startM); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        val todayEnd = cal.apply { set(java.util.Calendar.HOUR_OF_DAY, endH); set(java.util.Calendar.MINUTE, endM) }.timeInMillis

        val windowStart = if (now > todayEnd) todayStart + 86_400_000 else maxOf(now + 1_800_000, todayStart) // +30min min gap
        val windowEnd = if (now > todayEnd) todayEnd + 86_400_000 else todayEnd
        val nextTime = windowStart + (random.nextLong() % (windowEnd - windowStart)).coerceAtLeast(1800_000)
        lifetimeTimers[t.id] = nextTime
    }

    // ── Fire ──────────────────────────────────────────────────────

    private fun fireTrigger(trigger: Trigger) {
        val updated = trigger.copy(lastFired = System.currentTimeMillis())
        triggers.replaceAll { if (it.id == trigger.id) updated else it }
        onFire?.invoke(updated)
    }

    suspend fun join() { lifetimeJob?.join() }

    // ── Pre-built lifetime actions (真人感话题) ───────────────────

    val LIFETIME_TOPICS = listOf(
        "随机和用户聊聊今天的天气怎么样",
        "根据最近的工作记录总结一下进展，问问用户有没有需要帮助的",
        "检查一下系统状态，看看有没有需要更新的插件",
        "打开浏览器看看今天的头条新闻，和用户分享一条有趣的",
        "阅读一下最近的 Memory.md，找一找有没有未完成的事项提醒用户",
        "给用户推荐一个提高效率的小技巧",
        "分享一下今天学到的一个新知识或技术动态"
    )
}
