// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.trigger

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Random

/**
 * Trigger engine — CRON scheduler + lifetime "human-like" random triggers.
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
 * ## Persistence
 * Triggers are saved to {DataPaths.BASE}/triggers.json on every mutation
 * and reloaded in [load]. Call load() once at app startup.
 */
object TriggerEngine {
    private val triggers = mutableListOf<Trigger>()
    private val random = Random()
    private var lifetimeJob: Job? = null
    private var scope: CoroutineScope? = null

    /** Fuzzy window in minutes — CRON fires any time within [target, target+window]. */
    var cronFuzzyWindowMinutes: Int = 5

    // ── Data model ───────────────────────────────────────────────────

    @Serializable
    data class Trigger(
        val id: String,
        val type: TriggerType,
        val config: String,       // CRON: "min hour dom month dow"; LIFETIME: "HH:MM-HH:MM"
        val action: String,        // human-readable description of what to do
        val enabled: Boolean = true,
        val lastFired: Long = 0   // epoch millis of last fire
    )

    @Serializable
    enum class TriggerType { CRON, LIFETIME }

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
            KernelLog.w("TriggerEngine", "Failed to load triggers: ${e.message}")
        }
    }

    /** Persist triggers to disk. */
    private fun save() {
        try {
            val f = file
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(triggers.toList()))
        } catch (e: Exception) {
            KernelLog.w("TriggerEngine", "Failed to save triggers: ${e.message}")
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────

    fun addCron(id: String, cronExpr: String, action: String) {
        triggers.add(Trigger(id, TriggerType.CRON, cronExpr, action))
        save()
        registerCronAlarm()
    }

    fun addLifetime(id: String, timeRange: String, action: String) {
        triggers.add(Trigger(id, TriggerType.LIFETIME, timeRange, action))
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
    fun clear() { triggers.clear(); lifetimeJob?.cancel(); save() }

    // ── System wake & AlarmManager ───────────────────────────────────

    /**
     * Register a repeating system-level wake via AlarmManager.
     * WakeReceiver receives the intent and calls onSystemWake().
     */
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

    /**
     * Register the next precise Cron alarm with Android AlarmManager.
     * Finds the earliest upcoming CRON trigger and sets a one-shot RTC_WAKEUP alarm.
     */
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
                    // If this target has already passed today, push to tomorrow
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

            // Use setExact if available (API 19+), fallback to set
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
        triggers.filter { it.enabled && it.type == TriggerType.LIFETIME }.forEach { checkLifetime(it, now) }
        onWake?.invoke()
        // Re-register next Cron alarm (this one just fired or is close)
        registerCronAlarm()
    }

    // ── Internal: CRON matching with fuzzy window ────────────────────

    /**
     * Check if a CRON trigger should fire now.
     *
     * Uses a **fuzzy window**: matches if current minute falls within
     * [targetMinute, targetMinute + cronFuzzyWindowMinutes]. This means
     * a "0 9 * * *" trigger can fire anywhere from 9:00 to 9:05.
     *
     * The [lastFired] guard prevents re-firing within the same window:
     * once fired, the trigger won't fire again until at least
     * [cronFuzzyWindowMinutes] minutes have passed.
     */
    private fun checkCron(t: Trigger, now: Long) {
        val parts = t.config.split(" ").take(5)
        if (parts.size < 5) return

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val min = cal.get(java.util.Calendar.MINUTE)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 // Sunday=0

        // Day/month/dow must match exactly
        fun matchDay(expr: String, actual: Int): Boolean =
            expr == "*" || expr.split(",").any { it == actual.toString() }

        if (!matchDay(parts[2], day)) return
        if (!matchDay(parts[3], month)) return
        if (!matchDay(parts[4], dow)) return

        // Hour must match exactly
        if (parts[1] != "*" && hour != (parts[1].toIntOrNull() ?: hour)) return

        // Minute: fuzzy window [target, target+window]
        val targetMin = parts[0].toIntOrNull() ?: return
        val windowEnd = (targetMin + cronFuzzyWindowMinutes) % 60

        val inWindow = if (windowEnd > targetMin) {
            min in targetMin until windowEnd
        } else {
            // Window wraps around the hour boundary (e.g., 58→63 = 58,59,0,1,2)
            min >= targetMin || min < windowEnd
        }

        if (!inWindow) return

        // Guard: don't re-fire within the same window
        val msSinceLastFire = now - t.lastFired
        if (msSinceLastFire < cronFuzzyWindowMinutes * 60_000L) return

        fireTrigger(t)
    }

    // ── Internal: LIFETIME heartbeat + pre-generated daily slots ─────
    //
    // Design: each day, for each LIFETIME trigger, generate 1–3 random
    // minute-precise time slots within the active window (e.g. 10:00–20:00).
    // The poll loop (every 30s) checks if "now" falls within a ±2 min fuzzy
    // window of any unused slot. When matched, fire once and mark the slot used.
    // Slots regenerate at midnight.

    /** Pre-generated daily time slots: triggerId → list of "HH:MM" strings for today. */
    private val dailySlots = mutableMapOf<String, MutableList<String>>()

    /** Already-fired slots today: triggerId → set of "HH:MM". */
    private val firedSlots = mutableMapOf<String, MutableSet<String>>()

    /** Last day we generated slots for (day of year). */
    private var slotDay = -1

    private val lifetimeFuzzyMinutes = 2 // ±2 min window for heartbeat match

    private fun checkLifetime(t: Trigger, now: Long) {
        // Regenerate slots at start of a new day
        val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
            .get(java.util.Calendar.DAY_OF_YEAR)
        if (today != slotDay) {
            dailySlots.clear()
            firedSlots.clear()
            slotDay = today
        }

        // Ensure slots are generated for this trigger
        if (t.id !in dailySlots) {
            dailySlots[t.id] = generateDailySlots(t.config).toMutableList()
            firedSlots[t.id] = mutableSetOf()
        }

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val currentMin = cal.get(java.util.Calendar.MINUTE)
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val currentKey = "${currentHour.toString().padStart(2, '0')}:${currentMin.toString().padStart(2, '0')}"
        val prevMinKey = if (currentMin > 0)
            "${currentHour.toString().padStart(2, '0')}:${(currentMin - 1).toString().padStart(2, '0')}"
        else
            "${(if (currentHour > 0) currentHour - 1 else 23).toString().padStart(2, '0')}:59"

        val slots = dailySlots[t.id] ?: return
        val fired = firedSlots[t.id] ?: return

        // Match: current minute falls within fuzzy window of any unused slot
        for (slot in slots) {
            if (slot in fired) continue

            // Accept match if current time is within ±lifetimeFuzzyMinutes of the slot
            val slotParts = slot.split(":")
            val slotH = slotParts[0].toInt()
            val slotM = slotParts[1].toInt()

            val slotTotalMin = slotH * 60 + slotM
            val currentTotalMin = currentHour * 60 + currentMin

            val diff = kotlin.math.abs(currentTotalMin - slotTotalMin)
            // Handle day boundary wrap
            val diffWrap = kotlin.math.abs(currentTotalMin + 24 * 60 - slotTotalMin)
            val minDiff = minOf(diff, diffWrap)

            if (minDiff <= lifetimeFuzzyMinutes) {
                fired.add(slot)
                fireTrigger(t)
                return // Only fire once per check
            }
        }
    }

    /**
     * Generate 1–3 random time slots (HH:MM) within the given time range.
     * Slots are at least 30 minutes apart.
     */
    private fun generateDailySlots(config: String): List<String> {
        val range = config.split("-")
        if (range.size != 2) return emptyList()

        val startParts = range[0].split(":")
        val endParts = range[1].split(":")
        val startH = startParts[0].toIntOrNull() ?: 10
        val startM = startParts.getOrNull(1)?.toIntOrNull() ?: 0
        val endH = endParts[0].toIntOrNull() ?: 20
        val endM = endParts.getOrNull(1)?.toIntOrNull() ?: 0

        val windowStartMin = startH * 60 + startM
        val windowEndMin = endH * 60 + endM
        if (windowEndMin <= windowStartMin) return emptyList()

        // Generate 1–3 slots, each at least 30 min apart
        val count = random.nextInt(1, 4) // 1, 2, or 3
        val slots = mutableListOf<String>()
        val attempts = count * 5 // up to 5 attempts per slot to avoid infinite loop

        for (i in 0 until count) {
            var tries = 0
            while (tries < 5) {
                val randMin = windowStartMin + random.nextInt(windowEndMin - windowStartMin)
                val h = randMin / 60
                val m = randMin % 60
                val slot = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"

                // Ensure at least 30 min separation from other slots
                val tooClose = slots.any { existing ->
                    val eParts = existing.split(":")
                    val eMin = eParts[0].toInt() * 60 + eParts[1].toInt()
                    kotlin.math.abs(randMin - eMin) < 30
                }

                if (!tooClose) {
                    slots.add(slot)
                    break
                }
                tries++
            }
        }

        KernelLog.d("TriggerEngine", "Daily LIFETIME slots: ${slots.sorted()}")
        return slots.sorted()
    }

    // ── Internal: fire + background loop ─────────────────────────────

    private fun fireTrigger(trigger: Trigger) {
        val updated = trigger.copy(lastFired = System.currentTimeMillis())
        triggers.replaceAll { if (it.id == trigger.id) updated else it }
        save()
        KernelLog.d("TriggerEngine", "Fired: ${trigger.id} [${trigger.type}] → ${trigger.action.take(40)}")
        onFire?.invoke(updated)
    }

    /**
     * Start the background polling loop for LIFETIME triggers.
     * Also double-checks CRON triggers between AlarmManager wakeups.
     * Call once at app startup.
     */
    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        this.scope = scope
        lifetimeJob?.cancel()
        lifetimeJob = scope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    var hasActiveJobs = false
                    val snapshot = triggers.toList()
                    snapshot.filter { it.enabled }.forEach { trigger ->
                        when (trigger.type) {
                            TriggerType.CRON -> { checkCron(trigger, now); hasActiveJobs = true }
                            TriggerType.LIFETIME -> { checkLifetime(trigger, now); hasActiveJobs = true }
                        }
                    }
                    // 30s polling when triggers exist, 5min idle otherwise
                    delay(if (hasActiveJobs) 30_000L else 300_000L)
                } catch (_: CancellationException) { break }
                catch (e: Exception) {
                    KernelLog.w("TriggerEngine", "Poll loop error: ${e.message}")
                    delay(60_000L)
                }
            }
        }
    }

    fun stop() { lifetimeJob?.cancel(); lifetimeJob = null }

    /** Re-register Cron alarm after triggers change. Call from app layer when Context is ready. */
    fun refreshCronAlarm() {
        registerCronAlarm()
    }

    // ── LIFETIME topic pool ──────────────────────────────────────────

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
