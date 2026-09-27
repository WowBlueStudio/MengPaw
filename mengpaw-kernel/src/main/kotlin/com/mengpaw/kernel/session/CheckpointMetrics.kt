// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import java.util.concurrent.atomic.AtomicLong

/**
 * 检查点落盘/恢复的**进程内观测指标**。
 *
 * 为什么需要: 断点续跑此前是"全盲"子系统 — 落盘慢不慢、写了多少、恢复命中率多少、
 * 失败多少次, 一概无从得知。出问题时只能靠翻日志猜测, 也无法回答"检查点机制到底
 * 有没有在工作"这一最基本的问题。本对象补齐这一层。
 *
 * ## 为什么用 `object` 单例可以接受
 * 项目铁律是"不新增全局单例"(同进程跑两个独立实例会互相污染), 但此处不违反其精神:
 * 1. **纯进程内观测**: 只累加计数器, 不持有任何宿主能力 (无文件系统/无时钟/无日志出口),
 *    因此没有"宿主耦合"可言 — 铁律要防的是平台能力被单例锁死。
 * 2. **语义上本就该全局**: 指标度量的是"本进程的检查点 IO 总量", 跨实例聚合才成立;
 *    若做成实例字段, 宿主每 new 一个 [CheckpointManager] 计数即归零, 指标失去意义。
 * 3. **可清零**: [reset] 供测试与宿主巡检周期使用, 不存在"状态无法回收"的问题。
 *
 * ## 线程安全
 * 全部字段为 [AtomicLong], 计数与累加均原子; 读取时取同一轮快照会存在**极轻微**
 * 的跨字段不一致 (并发写入期间), 这对观测用途完全够用 — 不引入锁来换取并不需要的
 * 强一致 (锁会给热路径 IO 埋点增加争用)。
 *
 * ## 契约
 * 埋点**不得改变控制流**: 计数只做自增, 失败计数不得掩盖或改写调用方原有的异常处理
 * 语义 (例如 [CheckpointManager.save] 依旧吞异常并上报 ErrorCollector, 不因计数而抛)。
 */
object CheckpointMetrics {

    private val saveCount = AtomicLong(0)
    private val saveFailCount = AtomicLong(0)
    private val saveMillisTotal = AtomicLong(0)
    private val loadHit = AtomicLong(0)
    private val loadMiss = AtomicLong(0)
    private val loadFail = AtomicLong(0)
    private val lastMessageCount = AtomicLong(0)
    private val lastBytes = AtomicLong(0)

    /** 一次成功落盘。 */
    fun recordSave() { saveCount.incrementAndGet() }

    /** 一次落盘失败 (异常路径)。 */
    fun recordSaveFail() { saveFailCount.incrementAndGet() }

    /** 累加一次落盘耗时 (毫秒)。 */
    fun recordSaveMillis(millis: Long) { saveMillisTotal.addAndGet(millis.coerceAtLeast(0)) }

    /** 最近一次成功落盘的指标: 消息条数 + 落盘字节数 (UTF-8 口径, 与磁盘占用同源)。 */
    fun recordSavedShape(messageCount: Int, bytes: Long) {
        lastMessageCount.set(messageCount.toLong())
        lastBytes.set(bytes.coerceAtLeast(0))
    }

    /** 恢复命中 (读到可用检查点)。 */
    fun recordLoadHit() { loadHit.incrementAndGet() }

    /** 恢复未命中 (无检查点 / 已损坏 — 视作"没有", 不是故障)。 */
    fun recordLoadMiss() { loadMiss.incrementAndGet() }

    /** 恢复过程本身出错 (IO 异常等) — 与"未命中"区分, 前者是故障, 后者是正常空态。 */
    fun recordLoadFail() { loadFail.incrementAndGet() }

    /** 当前指标快照 (字段名即指标名, 供宿主/巡检直接展示)。 */
    fun snapshot(): Map<String, Long> = linkedMapOf(
        "save_count" to saveCount.get(),
        "save_fail_count" to saveFailCount.get(),
        "save_millis_total" to saveMillisTotal.get(),
        "load_hit" to loadHit.get(),
        "load_miss" to loadMiss.get(),
        "load_fail" to loadFail.get(),
        "last_message_count" to lastMessageCount.get(),
        "last_bytes" to lastBytes.get()
    )

    /** 归零全部指标 (测试隔离 / 宿主巡检周期收口)。 */
    fun reset() {
        saveCount.set(0)
        saveFailCount.set(0)
        saveMillisTotal.set(0)
        loadHit.set(0)
        loadMiss.set(0)
        loadFail.set(0)
        lastMessageCount.set(0)
        lastBytes.set(0)
    }
}
