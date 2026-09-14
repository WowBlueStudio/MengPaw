// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.harness

/**
 * Harness 平台抽象 — 时间源 (A 阶段, 2026-08-21)。
 *
 * 存在意义: kernel 内多处直接调 `System.currentTimeMillis()` / `System.nanoTime()`,
 * 非 JVM 目标不可用; 且真实时钟无法在测试中控制 (超时/退避/循环检测类逻辑测不准)。
 * 经本接口注入后, 测试可替换为可推进的假时钟。
 */
interface HarnessClock {
    /** 当前时刻 (epoch millis) — 对应 System.currentTimeMillis()。 */
    fun nowMillis(): Long

    /** 单调递增纳秒 — 对应 System.nanoTime(), 仅用于测间隔, 不用于绝对时间。 */
    fun nanoTime(): Long

    /** ISO-8601 本地日期 (yyyy-MM-dd) — 记忆分档按日期切文件, 与平台时区绑定, 故属平台能力。 */
    fun today(): String

    /** 当前时刻的 ISO-8601 字符串 (供审计/报告落盘)。 */
    fun nowIso(): String
}

/** [HarnessClock] 的 JVM 实现 — 使用 java.time (Android API 26+ / JVM 均可用)。 */
object JvmHarnessClock : HarnessClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
    override fun today(): String = java.time.LocalDate.now().toString()
    override fun nowIso(): String = java.time.OffsetDateTime.now().toString()
}
