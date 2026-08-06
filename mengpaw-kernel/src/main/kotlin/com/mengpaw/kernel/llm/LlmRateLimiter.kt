// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Global LLM concurrency limiter for single-user Android scenarios.
 *
 * Strategy: Semaphore + Random Jitter
 *   - Semaphore caps concurrent in-flight LLM calls (prevents burst 429s)
 *   - Random jitter (±25%) on retry delays (prevents thundering herd on reconnect)
 *   - Shared 429 backoff: when any call gets HTTP 429, all callers pause briefly
 *
 * Design rationale (Android single-user):
 *   - Semaphore is preferred over sliding-window QPM: Android runs one user at a time,
 *     peak concurrency is 3-5 calls (Mission workers + Heartbeat + manual chat). Sliding
 *     window adds timestamp-tracking complexity for no benefit.
 *   - Coordinated pause is simplified: single-process, so a shared atomic is sufficient;
 *     no need for IPC-based pause signaling.
 */
object LlmRateLimiter {

    /** Maximum concurrent LLM API calls. User-configurable via settings (SettingsViewModel). */
    @Volatile var maxConcurrency: Int = 10

    /** 在途调用计数 (锁保护) — 取代固定容量 Semaphore。 */
    private val lock = Any()
    private var inFlight = 0

    /** 等待轮询间隔 (ms) — 许可释放后最多延迟这么久被唤醒。 */
    private const val ACQUIRE_POLL_MS = 20L

    /** Timestamp of the most recent HTTP 429 response, used for coordinated backoff. */
    @Volatile private var last429Time: Long = 0L
    private const val COOLDOWN_AFTER_429_MS = 5_000L

    /** Notify the limiter that a 429 was received. Triggers a brief global pause. */
    fun report429() {
        last429Time = System.currentTimeMillis()
    }

    /**
     * Wait if a 429 cooldown is active.
     */
    private suspend fun await429Cooldown() {
        val elapsed = System.currentTimeMillis() - last429Time
        if (elapsed < COOLDOWN_AFTER_429_MS) {
            delay(COOLDOWN_AFTER_429_MS - elapsed)
        }
    }

    /**
     * Execute [block] under the concurrency limit.
     *
     * P2 修复: 原实现 Semaphore 固定 10 且 maxConcurrency 配置(设置页可调)从不生效 —
     * 旋钮失效。kotlinx Semaphore 容量不可动态调整, 故改用锁保护的计数器:
     * 每次进入实时读取 maxConcurrency, 支持运行中调整; 到达上限时轮询等待。
     * 单用户场景并发 ≤5, 轮询无饥饿与公平性问题。
     */
    suspend fun <T> withLimit(block: suspend () -> T): T {
        await429Cooldown()
        while (true) {
            val limit = maxConcurrency.coerceAtLeast(1)
            val acquired = synchronized(lock) {
                if (inFlight < limit) { inFlight++; true } else false
            }
            if (acquired) break
            delay(ACQUIRE_POLL_MS)
        }
        try {
            return block()
        } finally {
            synchronized(lock) { inFlight-- }
        }
    }

    /**
     * Apply random jitter to a retry delay.
     * Adds ±25% random variation to prevent thundering herd.
     */
    fun jitter(baseDelayMs: Long): Long {
        val jitterRange = (baseDelayMs * 0.25).toLong().coerceAtLeast(1)
        val offset = Random.nextLong(-jitterRange, jitterRange + 1)
        return (baseDelayMs + offset).coerceAtLeast(0)
    }
}
