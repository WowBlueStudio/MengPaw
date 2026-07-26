// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * Global LLM concurrency limiter for single-user Android scenarios.
 *
 * Strategy: Semaphore + Random Jitter
 *   - Semaphore caps concurrent in-flight LLM calls (prevents burst 429s)
 *   - Random jitter (±25%) on retry delays (prevents thundering herd on reconnect)
 *   - Shared 429 backoff: when any call gets HTTP 429, all callers pause briefly
 *
 * Usage:
 *   LlmRateLimiter.maxConcurrency = 10  // User-configurable via settings
 *   LlmRateLimiter.withLimit { ... }     // Wrap LLM API calls
 *
 * Design rationale (Android single-user):
 *   - Semaphore is preferred over sliding-window QPM: Android runs one user at a time,
 *     peak concurrency is 3-5 calls (Mission workers + Heartbeat + manual chat). Sliding
 *     window adds timestamp-tracking complexity for no benefit.
 *   - Coordinated pause is simplified: single-process, so a shared atomic is sufficient;
 *     no need for IPC-based pause signaling.
 */
object LlmRateLimiter {

    /** Maximum concurrent LLM API calls. User-adjustable in settings. Default matches QwenPaw. */
    @Volatile var maxConcurrency: Int = 10
        set(value) {
            field = value.coerceIn(1, 50)
            // Recreate semaphore with new limit
            semaphore = Semaphore(field)
        }

    @Volatile private var semaphore: Semaphore = Semaphore(maxConcurrency)

    /** Timestamp of the most recent HTTP 429 response, used for coordinated backoff. */
    @Volatile private var last429Time: Long = 0L
    private const val COOLDOWN_AFTER_429_MS = 5_000L

    /** Notify the limiter that a 429 was received. Triggers a brief global pause. */
    fun report429() {
        last429Time = System.currentTimeMillis()
    }

    /**
     * Wait if the system is in a post-429 cooldown period.
     * Returns immediately if no recent 429 was reported.
     */
    private suspend fun await429Cooldown() {
        val elapsed = System.currentTimeMillis() - last429Time
        if (elapsed < COOLDOWN_AFTER_429_MS) {
            delay(COOLDOWN_AFTER_429_MS - elapsed)
        }
    }

    /**
     * Execute [block] under the concurrency limit.
     * - Acquires a semaphore permit (suspends if at capacity)
     * - Checks for post-429 cooldown before proceeding
     *
     * @return the result of [block]
     */
    suspend fun <T> withLimit(block: suspend () -> T): T {
        await429Cooldown()
        return semaphore.withPermit { block() }
    }

    /**
     * Apply random jitter to a retry delay.
     * Adds ±25% random variation to prevent thundering herd.
     *
     * @param baseDelayMs the base delay in milliseconds
     * @return jittered delay in milliseconds
     */
    fun jitter(baseDelayMs: Long): Long {
        val jitterRange = (baseDelayMs * 0.25).toLong().coerceAtLeast(1)
        val offset = Random.nextLong(-jitterRange, jitterRange + 1)
        return (baseDelayMs + offset).coerceAtLeast(0)
    }
}
