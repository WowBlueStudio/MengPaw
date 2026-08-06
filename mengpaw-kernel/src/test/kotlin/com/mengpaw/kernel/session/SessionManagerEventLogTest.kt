// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class SessionManagerEventLogTest {
    // ── EventLog (in-memory coverage) ───────────────────────────────

    @Test
    fun `recordSessionEvent emits on event bus`() {
        val manager = SessionManager()
        val session = manager.createSession("Test")
        val events = mutableListOf<SessionEventBus.SessionEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            SessionEventBus.events.collect { events.add(it) }
        }
        try {
            manager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.TOOL_EXECUTED,
                sessionId = session.id,
                agentName = "test",
                summary = "test event"
            ))
            // Give time for the shared flow to deliver
            Thread.sleep(100)
            assertTrue(events.any { it.kind == SessionEventBus.EventKind.TOOL_EXECUTED })
        } finally {
            job.cancel()
        }
    }
}
