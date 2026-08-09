// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 高危操作用户确认总线 (v0.34.3 分级系统) — 内核挂起等待用户确认, shell 弹窗回传。
 *
 * [request] 挂起当前协程直到 [respond] 或超时; **无 UI 监听器 (worker/后台) 或超时
 * 一律默认拒绝** (安全默认)。请求带命令+意图声明 (reason), 供弹窗展示。
 */
object UserConfirmBus {

    /** 一次待确认请求 — shell 弹窗展示, 经 [respond] 回传。 */
    data class ConfirmRequest(
        val id: Long,
        val command: String,
        val reason: String?,
        val riskLabel: String,
        val timeoutMs: Long
    )

    fun interface Listener {
        /** 返回 true = 已展示给用户并会回传结果; false = 未处理 (请求立即按拒绝收尾)。 */
        fun onRequest(request: ConfirmRequest): Boolean
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val idSeq = AtomicLong(0)

    fun registerListener(l: Listener) { listeners.add(l) }
    fun unregisterListener(l: Listener) { listeners.remove(l) }

    /** 挂起等待用户确认。@return true=允许; false=拒绝/超时/无监听器。 */
    suspend fun request(
        command: String,
        reason: String?,
        riskLabel: String,
        timeoutMs: Long = 30_000L
    ): Boolean {
        val id = idSeq.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        val delivered = listeners.any { it.onRequest(ConfirmRequest(id, command, reason, riskLabel, timeoutMs)) }
        return try {
            if (!delivered) false // 无 UI (worker/后台) → 默认拒绝
            else withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            pending.remove(id)
        }
    }

    /** shell 弹窗结果回传。 */
    fun respond(id: Long, allowed: Boolean) {
        pending.remove(id)?.complete(allowed)
    }
}
