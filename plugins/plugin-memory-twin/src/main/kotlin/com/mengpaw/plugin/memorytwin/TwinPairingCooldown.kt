// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

/**
 * 配对冷却/限流 — 从 TwinPairingEngine 拆分 (P1.1: 暴力尝试防护)。
 *
 * 每个 peer 在时间窗口内最多 [MAX_ATTEMPTS] 次尝试, 超限锁定 [LOCK_DURATION_MS]。
 * 状态独立持有, 行为与拆分前完全一致。
 */
internal class TwinPairingCooldown {

    /** Max pairing attempts within the window before locking. */
    private val maxAttempts = 3
    /** Time window for counting attempts (milliseconds). */
    private val attemptWindowMs = 10 * 60 * 1000L // 10 minutes
    /** Lock duration after exceeding max attempts (milliseconds). */
    private val lockDurationMs = 30 * 60 * 1000L  // 30 minutes

    /** PeerId → list of recent attempt timestamps (trimmed on each check). */
    private val attemptHistory = mutableMapOf<String, MutableList<Long>>()
    /** PeerId → when its lock expires (0 = not locked). */
    private val lockExpiry = mutableMapOf<String, Long>()

    /** Check if pairing is allowed for [peerId]; returns error message or null. */
    fun checkPairingCooldown(peerId: String): String? {
        // Trim expired lock
        val now = System.currentTimeMillis()
        lockExpiry[peerId]?.let { expiry ->
            if (now < expiry) {
                val remainingMin = (expiry - now) / 60_000
                return "配对冷却中 — 该设备在 $remainingMin 分钟后才可再次发起配对"
            } else {
                lockExpiry.remove(peerId)
                attemptHistory.remove(peerId)
            }
        }
        // Trim old attempts outside the window
        val recent = attemptHistory.getOrPut(peerId) { mutableListOf() }
        recent.removeAll { now - it > attemptWindowMs }
        // Check limit
        if (recent.size >= maxAttempts) {
            lockExpiry[peerId] = now + lockDurationMs
            recent.clear()
            return "配对请求过于频繁 — 该设备已被锁定 30 分钟（10 分钟内最多 3 次尝试）"
        }
        // Record this attempt
        recent.add(now)
        return null
    }

    /** Clear cooldown state for a peer (called on successful pairing). */
    fun clearPairingCooldown(peerId: String) {
        attemptHistory.remove(peerId)
        lockExpiry.remove(peerId)
    }
}
