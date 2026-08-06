// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.trigger

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Random

/**
 * Trigger engine — CRON scheduler + SCHEDULE "daily alarm" random triggers.
 *
 * ## How CRON works
 * 1. Android AlarmManager wakes the device every ~10 minutes (via WakeReceiver).
 * 2. onSystemWake() → checkCron() matches current time against each enabled CRON trigger.
 * 3. CRON uses a **fuzzy 5-minute window**: a "0 9 * * *" trigger fires any time
 *    between 9:00–9:05, not precisely at 9:00:00. This coarseness matches user
 *    perception ("around 9am") and avoids missing a narrow 1-minute slot.
 * 4. When matched, fireTrigger() invokes [onFire] callback — the app layer must
 *    set this to actually execute trigger actions.
 * 5. A "last fired minute" guard prevents double-firing within the same window.
 *
 * ## How SCHEDULE works
 * 1. Create a SCHEDULE trigger with: time window (e.g. 08:00-22:00), count (e.g. 3),
 *    and min interval (e.g. 60min).
 * 2. At start of each day, engine generates `count` random time slots within the window,
 *    each at least `interval` minutes apart.
 * 3. A background poll (30s) checks if current time falls within ±5 min of any unused slot.
 * 4. When matched, fire once and mark slot used. All slots regenerate at midnight.
 *
 * ## Persistence
 * Triggers are saved to {DataPaths.BASE}/triggers.json on every mutation
 * and reloaded in [load]. Call load() once at app startup.
 */
object TriggerEngine {
    // 并发安全: 轮询协程 (start) 与系统唤醒 (onSystemWake)/UI 增删可并发 —
    // CopyOnWriteArrayList 快照迭代, 杜绝 filter/replaceAll 与 add 并发 CME
    private val triggers = java.util.concurrent.CopyOnWriteArrayList<Trigger>()
    private val random = Random()
    private var pollJob: Job? = null
    private var scope: CoroutineScope? = null

    /** Fuzzy window in minutes — CRON fires any time within [target, target+window]. */
    var cronFuzzyWindowMinutes: Int = 5

    /** Fuzzy window in minutes — SCHEDULE fires any time within [slot, slot+window]. */
    var scheduleFuzzyWindowMinutes: Int = 5

    // ── Data model ───────────────────────────────────────────────────

    @Serializable
    data class Trigger(
        val id: String,
        val type: TriggerType,
        val config: String,       // CRON: "min hour dom month dow"
                                  // SCHEDULE: "HH:MM-HH:MM,count=N,interval=M"
        val action: String,        // human-readable description of what to do
        val enabled: Boolean = true,
        val lastFired: Long = 0   // epoch millis of last fire
    )

    @Serializable
    enum class TriggerType { CRON, SCHEDULE }

    // ── Callbacks ────────────────────────────────────────────────────

    /** Called when any trigger fires. Set this to execute trigger actions. */
    var onFire: ((Trigger) -> Unit)? = null

    /** Called on every system wake (AlarmManager / power event). */
    var onWake: (() -> Unit)? = null

    /** Android Context, stored for AlarmManager registration. */
    @Volatile
    private var appContext: Any? = null

    /** Store the Android context for Cron alarm registration. Call once at startup. */
    fun setContext(context: Any? /* android.content.Context */) {
        appContext = context
    }

    // ── Persistence ──────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(DataPaths.BASE, "triggers.json")

    /** Load triggers from disk. Call once at app startup after DataPaths is ready. */
    fun load() {
        try {
            val f = file
            if (!f.exists()) return
            val text = f.readText()
            if (text.isBlank()) return
            val loaded: List<Trigger> = json.decodeFromString(text)
            triggers.clear()
            triggers.addAll(loaded)
            KernelLog.d("TriggerEngine", "Loaded ${triggers.size} triggers from disk")
        } catch (e: Exception) {
            KernelLog.w("TriggerEngine", "Corrupted triggers.json, resetting: ${e.message}")
            // Delete corrupted file so next save starts fresh
            try { file.delete() } catch (_: Exception) {}
            triggers.clear()
        }
    }

    /** Persist triggers to disk. Uses atomic write (tmp + rename) to prevent corruption on crash. */
    private fun save() {
        try {
            file.atomicWriteText(json.encodeToString(triggers.toList()))
        } catch (e: Exception) {
            KernelLog.w("TriggerEngine", "Failed to save triggers: ${e.message}")
        }
    }

    /**
     * 标准原子写: 先写同目录 `.tmp`，再 Files.move(REPLACE_EXISTING) 覆盖。
     * 同目录内 rename 原子, 崩溃时要么旧文件完好要么新文件完整；
     * Windows 上 File.renameTo 无法覆盖已存在目标 (返回 false 且静默不更新),
     * Files.move 可替换 — 失败时原文件保持完好。
     */
    private fun File.atomicWriteText(text: String) {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        try {
            tmp.writeText(text)
            java.nio.file.Files.move(
                tmp.toPath(), this.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            // 失败保留原文件, 清理残留 tmp 后上报
            try { tmp.delete() } catch (_: Exception) {}
            ErrorCollector.report(e, "TriggerEngine.atomicWriteText")
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────

    fun addCron(id: String, cronExpr: String, action: String) {
        triggers.add(Trigger(id, TriggerType.CRON, cronExpr, action))
        save()
        registerCronAlarm()
    }

    /**
     * Add a SCHEDULE trigger with daily alarm slots.
     * @param id Unique trigger ID
     * @param config "HH:MM-HH:MM,count=N,interval=M" e.g. "08:00-22:00,count=3,interval=60"
     * @param action Description of what to do when fired
     */
    fun addSchedule(id: String, config: String, action: String) {
        triggers.add(Trigger(id, TriggerType.SCHEDULE, config, action))
        save()
    }

    fun remove(id: String) {
        triggers.removeAll { it.id == id }
        save()
    }

    fun enable(id: String) {
        triggers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
            triggers[it] = triggers[it].copy(enabled = true)
            save()
        }
    }

    fun disable(id: String) {
        triggers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
            triggers[it] = triggers[it].copy(enabled = false)
            save()
        }
    }

    fun list(): List<Trigger> = triggers.toList()
    fun clear() { triggers.clear(); pollJob?.cancel(); save() }

    // ── System wake & AlarmManager ───────────────────────────────────

    fun registerSystemWake(context: Any? /* android.content.Context */, intervalMinutes: Int = 10) {
        if (context == null) return
        try {
            val ctxClass = Class.forName("android.content.Context")
            val alarmClass = Class.forName("android.app.AlarmManager")
            val intentClass = Class.forName("android.content.Intent")
            val pendingIntentClass = Class.forName("android.app.PendingIntent")

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

            KernelLog.d("TriggerEngine", "System wake registered every ${intervalMinutes}min")
        } catch (e: Exception) {
            KernelLog.w("TriggerEngine", "AlarmManager not available: ${e.message}")
        }
    }

    fun registerCronAlarm() {
        val ctx = appContext ?: run {
            KernelLog.d("TriggerEngine", "Cron alarm skipped: no Context stored")
            return
        }
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
                    if (timeInMillis <= now.timeInMillis) add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                if (target.timeInMillis < nextTime) nextTime = target.timeInMillis
            }

            if (nextTime == Long.MAX_VALUE) return

            val ctxClass = Class.forName("android.content.Context")
            val alarmClass = Class.forName("android.app.AlarmManager")
            val intentClass = Class.forName("android.content.Intent")
            val pendingIntentClass = Class.forName("android.app.PendingIntent")

            val alarmManager = ctxClass.getMethod("getSystemService", String::class.java).invoke(ctx, "alarm")
            val intent = intentClass.getConstructor(ctxClass, Class.forName("java.lang.Class"))
                .newInstance(ctx, Class.forName("com.mengpaw.shell.service.WakeReceiver"))
            intent.javaClass.getMethod("putExtra", String::class.java, String::class.java)
                .invoke(intent, "wake_reason", "cron")

            val pendingIntent = pendingIntentClass.getMethod("getBroadcast",
                ctxClass, Int::class.javaPrimitiveType, intentClass, Int::class.javaPrimitiveType)
                .invoke(null, ctx, 1001, intent,
                    pendingIntentClass.getField("FLAG_IMMUTABLE").getInt(null) or
                    pendingIntentClass.getField("FLAG_UPDATE_CURRENT").getInt(null))

            try {
                alarmManager?.javaClass?.getMethod("setExact",
                    Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, pendingIntentClass)
                    ?.invoke(alarmManager,
                        alarmClass.getField("RTC_WAKEUP").getInt(null), nextTime, pendingIntent)
            } catch (_: Exception) {
                alarmManager?.javaClass?.getMethod("set",
                    Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, pendingIntentClass)
                    ?.invoke(alarmManager,
                        alarmClass.getField("RTC_WAKEUP").getInt(null), nextTime, pendingIntent)
            }

            KernelLog.d("TriggerEngine", "Next Cron wake: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(nextTime))}")
        } catch (e: Exception) {
            KernelLog.w("TriggerEngine", "Cron alarm registration failed: ${e.message}")
        }
    }

    /** Called by WakeReceiver / EventReceiver when the system wakes. */
    fun onSystemWake() {
        val now = System.currentTimeMillis()
        triggers.filter { it.enabled && it.type == TriggerType.CRON }.forEach { checkCron(it, now) }
        triggers.filter { it.enabled && it.type == TriggerType.SCHEDULE }.forEach { checkSchedule(it, now) }
        onWake?.invoke()
        registerCronAlarm()
    }

    // ── Internal: CRON matching with fuzzy window ────────────────────

    private fun checkCron(t: Trigger, now: Long) {
        val parts = t.config.split(" ").take(5)
        if (parts.size < 5) return

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val min = cal.get(java.util.Calendar.MINUTE)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1

        fun matchDay(expr: String, actual: Int): Boolean =
            expr == "*" || expr.split(",").any { it == actual.toString() }

        if (!matchDay(parts[2], day)) return
        if (!matchDay(parts[3], month)) return
        if (!matchDay(parts[4], dow)) return

        if (parts[1] != "*" && hour != (parts[1].toIntOrNull() ?: hour)) return

        val targetMin = parts[0].toIntOrNull() ?: return
        val windowEnd = (targetMin + cronFuzzyWindowMinutes) % 60

        val inWindow = if (windowEnd > targetMin) {
            min in targetMin until windowEnd
        } else {
            min >= targetMin || min < windowEnd
        }

        if (!inWindow) return

        val msSinceLastFire = now - t.lastFired
        if (msSinceLastFire < cronFuzzyWindowMinutes * 60_000L) return

        fireTrigger(t)
    }

    // ── Internal: SCHEDULE daily alarm slots ─────────────────────────
    //
    // Design: each day, for each SCHEDULE trigger, generate N random
    // minute-precise time slots within the active window (e.g. 08:00–22:00),
    // each at least `interval` minutes apart. Config format:
    //   "HH:MM-HH:MM,count=N,interval=M"
    // Defaults: window=08:00-22:00, count=3, interval=60
    // The poll loop (every 30s) checks if "now" falls within a ±5 min fuzzy
    // window of any unused slot. When matched, fire once and mark the slot used.
    // Slots regenerate at midnight.

    private val dailySlots = mutableMapOf<String, MutableList<String>>()
    private val firedSlots = mutableMapOf<String, MutableSet<String>>()
    private var slotDay = -1

    private data class ScheduleConfig(
        val windowStartMin: Int = 8 * 60,
        val windowEndMin: Int = 22 * 60,
        val count: Int = 3,
        val minInterval: Int = 60
    )

    private fun parseScheduleConfig(config: String): ScheduleConfig {
        val parts = config.split(",")
        if (parts.isEmpty()) return ScheduleConfig()

        val range = parts[0].split("-")
        val windowStartMin = if (range.size == 2) {
            val sh = range[0].split(":").getOrNull(0)?.toIntOrNull() ?: 8
            val sm = range[0].split(":").getOrNull(1)?.toIntOrNull() ?: 0
            sh * 60 + sm
        } else 8 * 60

        val windowEndMin = if (range.size == 2) {
            val eh = range[1].split(":").getOrNull(0)?.toIntOrNull() ?: 22
            val em = range[1].split(":").getOrNull(1)?.toIntOrNull() ?: 0
            eh * 60 + em
        } else 22 * 60

        var count = 3
        var minInterval = 60
        for (i in 1 until parts.size) {
            val kv = parts[i].split("=")
            if (kv.size == 2) {
                when (kv[0].trim().lowercase()) {
                    "count" -> count = kv[1].toIntOrNull() ?: 3
                    "interval" -> minInterval = kv[1].toIntOrNull() ?: 60
                }
            }
        }

        return ScheduleConfig(
            windowStartMin = windowStartMin.coerceIn(0, 24 * 60 - 1),
            windowEndMin = windowEndMin.coerceIn(1, 24 * 60),
            count = count.coerceIn(1, 24),
            minInterval = minInterval.coerceIn(15, 8 * 60)
        )
    }

    private fun checkSchedule(t: Trigger, now: Long) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (today != slotDay) {
            dailySlots.clear()
            firedSlots.clear()
            slotDay = today
        }

        if (t.id !in dailySlots) {
            dailySlots[t.id] = generateScheduleSlots(t.config).toMutableList()
            firedSlots[t.id] = mutableSetOf()
            KernelLog.d("TriggerEngine", "Daily SCHEDULE [${t.id}] slots: ${dailySlots[t.id]}")
        }

        val currentTotalMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val slots = dailySlots[t.id] ?: return
        val fired = firedSlots[t.id] ?: return

        for (slot in slots) {
            if (slot in fired) continue

            val slotParts = slot.split(":")
            val slotTotalMin = slotParts[0].toInt() * 60 + slotParts[1].toInt()

            val diff = kotlin.math.abs(currentTotalMin - slotTotalMin)
            val diffWrap = kotlin.math.abs(currentTotalMin + 24 * 60 - slotTotalMin)
            val minDiff = minOf(diff, diffWrap)

            if (minDiff <= scheduleFuzzyWindowMinutes) {
                fired.add(slot)
                fireTrigger(t)
                return
            }
        }
    }

    private fun generateScheduleSlots(config: String): List<String> {
        val cfg = parseScheduleConfig(config)
        if (cfg.windowEndMin <= cfg.windowStartMin) return emptyList()
        val available = cfg.windowEndMin - cfg.windowStartMin
        val minRequired = cfg.count * cfg.minInterval
        if (minRequired > available) {
            val adjustedCount = (available / cfg.minInterval).coerceAtLeast(1)
            KernelLog.w("TriggerEngine", "Schedule $config needs $minRequired min but only $available available, using count=$adjustedCount")
            return generateScheduleSlots("${config.split(",")[0]},count=$adjustedCount,interval=${cfg.minInterval}")
        }

        val slots = mutableListOf<Int>()
        var attempt = 0
        while (attempt < cfg.count * 20 && slots.size < cfg.count) {
            val randMin = cfg.windowStartMin + random.nextInt(available)
            val tooClose = slots.any { kotlin.math.abs(randMin - it) < cfg.minInterval }
            if (!tooClose) {
                slots.add(randMin)
            }
            attempt++
        }

        return slots.sorted().map { totalMin ->
            "${(totalMin / 60).toString().padStart(2, '0')}:${(totalMin % 60).toString().padStart(2, '0')}"
        }
    }

    // ── Internal: fire + background loop ─────────────────────────────

    private fun fireTrigger(trigger: Trigger) {
        val updated = trigger.copy(lastFired = System.currentTimeMillis())
        triggers.replaceAll { if (it.id == trigger.id) updated else it }
        save()
        KernelLog.d("TriggerEngine", "Fired: ${trigger.id} [${trigger.type}] → ${trigger.action.take(40)}")
        onFire?.invoke(updated)
    }

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        this.scope = scope
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    var hasActiveJobs = false
                    val snapshot = triggers.toList()
                    snapshot.filter { it.enabled }.forEach { trigger ->
                        when (trigger.type) {
                            TriggerType.CRON -> { checkCron(trigger, now); hasActiveJobs = true }
                            TriggerType.SCHEDULE -> { checkSchedule(trigger, now); hasActiveJobs = true }
                        }
                    }
                    delay(if (hasActiveJobs) 30_000L else 300_000L)
                } catch (_: CancellationException) { break }
                catch (e: Exception) {
                    KernelLog.w("TriggerEngine", "Poll loop error: ${e.message}")
                    delay(60_000L)
                }
            }
        }
    }

    fun stop() { pollJob?.cancel(); pollJob = null }

    fun refreshCronAlarm() {
        registerCronAlarm()
    }

    // ── SCHEDULE topic pool ──────────────────────────────────────────

    val SCHEDULE_TOPICS = listOf(
        "随机和用户聊聊今天的天气怎么样",
        "根据最近的工作记录总结一下进展，问问用户有没有需要帮助的",
        "检查一下系统状态，看看有没有需要更新的插件",
        "打开浏览器看看今天的头条新闻，和用户分享一条有趣的",
        "阅读一下 memory/ 目录里的近期记忆，找一找有没有未完成的事项提醒用户",
        "给用户推荐一个提高效率的小技巧",
        "分享一下今天学到的一个新知识或技术动态"
    )
}
