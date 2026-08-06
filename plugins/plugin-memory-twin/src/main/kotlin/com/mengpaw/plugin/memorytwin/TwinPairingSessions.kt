// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 配对会话存储 — 从 TwinPairingEngine 拆分 (职责: 会话生命周期管理)。
 *
 * 会话增删查 / 120s 超时自动清理 / 取消与拒绝。行为与拆分前完全一致。
 */
internal class TwinPairingSessions(
    val scope: CoroutineScope
) {

    /** Active pairing sessions, keyed by sessionId. */
    val sessions = mutableMapOf<String, TwinPairingEngine.PairingSession>()

    /** Auto-cleanup stale sessions after 120 seconds. */
    fun scheduleTimeout(sessionId: String) {
        scope.launch {
            delay(120_000) // 2 minutes
            val session = sessions[sessionId] ?: return@launch
            if (session.phase != TwinPairingEngine.PairingPhase.ESTABLISHED) {
                android.util.Log.i("MengPawTwin", "配对超时: $sessionId")
                sessions.remove(sessionId)
            }
        }
    }

    /** Cancel an active pairing session. */
    fun cancel(sessionId: String) {
        sessions[sessionId]?.phase = TwinPairingEngine.PairingPhase.CANCELLED
        sessions.remove(sessionId)
        android.util.Log.i("MengPawTwin", "配对取消: $sessionId")
    }

    /** Reject a pairing request (responder-side cancel). */
    fun reject(peerId: String) {
        sessions.values
            .filter { it.peerId == peerId && !it.isInitiator }
            .forEach { it.phase = TwinPairingEngine.PairingPhase.CANCELLED }
        sessions.values.removeAll { it.peerId == peerId && !it.isInitiator }
    }

    /** Get the current pairing state for UI display. */
    fun getSession(sessionId: String): TwinPairingEngine.PairingSession? = sessions[sessionId]

    /** Find an active session for a given peer. */
    fun getSessionForPeer(peerId: String): TwinPairingEngine.PairingSession? =
        sessions.values.find { it.peerId == peerId && it.phase != TwinPairingEngine.PairingPhase.CANCELLED }

    /** Clean up all expired sessions. */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 120_000
        sessions.values.removeAll { it.createdAt < cutoff && it.phase != TwinPairingEngine.PairingPhase.ESTABLISHED }
    }
}
