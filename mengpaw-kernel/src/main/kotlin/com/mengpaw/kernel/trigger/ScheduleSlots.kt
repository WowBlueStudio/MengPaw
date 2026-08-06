// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.trigger

import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.trigger.TriggerEngine.Trigger
import java.util.Random

/**
 * SCHEDULE 每日随机槽位引擎（自 TriggerEngine 拆出 — 400 行文件拆分批次 1）。
 *
 * Design: 每天对每个 SCHEDULE trigger 在活跃窗口 (如 08:00–22:00) 内生成 N 个
 * 分钟精度随机槽位, 两两至少间隔 `interval` 分钟。配置格式:
 *   "HH:MM-HH:MM,count=N,interval=M" (默认: 08:00-22:00, count=3, interval=60)。
 * 轮询循环 (30s) 检查 "now" 是否落在某未用槽位的 ±5 分钟模糊窗口内,
 * 命中则触发一次并标记槽位已用; 午夜槽位全部重生成。
 *
 * 触发回调 [onFire] 由 TriggerEngine 注入 (fireTrigger — 更新 lastFired + 持久化 + 通知)。
 */
internal class ScheduleSlotEngine(
    private val onFire: (Trigger) -> Unit
) {
    private val dailySlots = mutableMapOf<String, MutableList<String>>()
    private val firedSlots = mutableMapOf<String, MutableSet<String>>()
    private var slotDay = -1
    private val random = Random()

    /** 检查 [now] 是否落在任何 SCHEDULE 触发器的未用槽位模糊窗口内。 */
    fun check(t: Trigger, now: Long, fuzzyWindowMinutes: Int) {
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

            if (minDiff <= fuzzyWindowMinutes) {
                fired.add(slot)
                onFire(t)
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
}
