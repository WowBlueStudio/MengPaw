// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Session lifecycle event bus.
 *
 * Architecture (matching OpenClaw session-state-events.ts + session-state-notices.ts):
 *   session lifecycle events → SharedFlow → subscribers (SessionManager, AgentViewModel, etc.)
 *
 * - Events are in-memory only (ephemeral); use SessionEventLog for durable persistence.
 * - Uses [MutableSharedFlow] with replay=0 and extraBufferCapacity=128 so slow subscribers
 *   don't block emitters.
 * - Same pattern as NotifyBus but for session lifecycle instead of agent→user notifications.
 */
object SessionEventBus {

    enum class EventKind {
        /** A new session was created. */
        SESSION_CREATED,
        /** A user message was appended. */
        USER_MESSAGE,
        /** An LLM call was initiated. */
        LLM_CALL_STARTED,
        /** An LLM call completed successfully. */
        LLM_CALL_COMPLETED,
        /** An LLM call failed with an error. */
        LLM_CALL_ERROR,
        /** A tool was executed. */
        TOOL_EXECUTED,
        /** A run (ReAct loop) completed normally. */
        RUN_COMPLETED,
        /** A run (ReAct loop) failed. */
        RUN_FAILED,
        /** A run (ReAct loop) was interrupted (network, exception, user cancel). */
        RUN_INTERRUPTED,
        /** Session history was compacted. */
        SESSION_COMPACTED,
        /** A previously interrupted session was recovered via recovery block injection. */
        SESSION_RECOVERED
    }

    /**
     * A single session lifecycle event.
     *
     * @property kind the event type — drives subscriber dispatch
     * @property sessionId the affected session UUID
     * @property agentName the agent handling this session
     * @property summary human-readable one-line summary
     * @property payload optional structured metadata (e.g. error message, tool name, token count)
     */
    data class SessionEvent(
        val kind: EventKind,
        val sessionId: String,
        val agentName: String,
        val summary: String,
        val payload: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = 128)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /**
     * Emit a session lifecycle event.
     * Non-blocking: uses tryEmit internally. If the buffer is full, the event is dropped
     * (same philosophy as OpenClaw session-state-notices — ephemeral notifications).
     */
    fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}
