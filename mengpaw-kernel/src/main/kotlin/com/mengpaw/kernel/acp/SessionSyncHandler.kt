// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.session.SessionEventBus
import com.mengpaw.kernel.session.SessionManager
import com.mengpaw.kernel.DataPaths
import java.io.File
import kotlinx.serialization.json.*

/**
 * ACP handler for session synchronization (upstream links).
 *
 * Enables two paired devices to sync session event timelines:
 * - SESSION_HEAD: exchange latest event sequence numbers for known sessions
 * - SESSION_PULL: request events after a given sequence
 * - SESSION_DELTA: transmit session event deltas
 * - SESSION_ACK: acknowledge receipt
 *
 * Architecture (matching OpenClaw session-state-events.ts):
 *   SESSION_HEAD  → compare lastSequence
 *   SESSION_PULL  → listEventsSince(afterSeq)
 *   SESSION_DELTA → recordSessionEvent for each delta
 *   SESSION_ACK   → mark peer as synced
 *
 * This is the session sync layer on top of ACP's transport/auth infrastructure.
 * Paired with a bridge adapter, this same handler enables Claude Codex integration.
 */
class SessionSyncHandler(
    private val sessionManager: SessionManager,
    private val agentName: String
) : AcpHandler {

    private val json = Json { ignoreUnknownKeys = true }

    override val supportedTypes: List<AcpMessageType> = listOf(
        AcpMessageType.SESSION_HEAD,
        AcpMessageType.SESSION_PULL,
        AcpMessageType.SESSION_DELTA,
        AcpMessageType.SESSION_ACK
    )

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        val type = try { AcpMessageType.valueOf(message.type) } catch (_: Exception) { return null }
        if (type !in supportedTypes) return null

        return try {
            when (type) {
                AcpMessageType.SESSION_HEAD -> handleSessionHead(message)
                AcpMessageType.SESSION_PULL -> handleSessionPull(message)
                AcpMessageType.SESSION_DELTA -> handleSessionDelta(message)
                AcpMessageType.SESSION_ACK -> handleSessionAck(message)
                else -> null
            }
        } catch (e: Exception) {
            AcpResult(false, "SessionSync error: ${e.message?.take(200)}")
        }
    }

    // ── SESSION_HEAD: exchange latest event sequences ────────────────

    /**
     * Peer sends their latest event sequence for a session.
     * We respond with our latest sequence for the same session.
     *
     * This is the "握手" step — both sides learn if they're in sync.
     */
    private suspend fun handleSessionHead(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_payload")
        val sessionKey = payload["sessionKey"]?.jsonPrimitive?.content ?: ""
        val peerSequence = payload["lastSequence"]?.jsonPrimitive?.int ?: 0

        // Count events from log file (line count = auto-increment sequence)
        val logFile = File(DataPaths.dialogArchiveDir(agentName), "${sessionKey}.event.log")
        val localCount = if (logFile.exists()) {
            try { logFile.useLines { it.count() } } catch (_: Exception) { 0 }
        } else 0

        return AcpResult(true, buildJsonObject {
            put("sessionKey", JsonPrimitive(sessionKey))
            put("mySequence", JsonPrimitive(localCount))
            put("peerSequence", JsonPrimitive(peerSequence))
            put("inSync", JsonPrimitive(localCount >= peerSequence))
        }.toString())
    }

    // ── SESSION_PULL: request events after a sequence ───────────────

    /**
     * Peer requests events they don't have yet.
     * We return events after [afterSequence] up to [limit].
     *
     * Matching OpenClaw listSessionStateEventsSince(sessionKey, agentId, afterSeq, limit).
     */
    private suspend fun handleSessionPull(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_payload")
        val sessionKey = payload["sessionKey"]?.jsonPrimitive?.content ?: ""
        val afterSequence = payload["afterSequence"]?.jsonPrimitive?.int ?: 0
        val limit = payload["limit"]?.jsonPrimitive?.int ?: 50

        val events = sessionManager.listEventsSince(sessionKey, afterSeq = afterSequence, limit = limit)
        val eventJson = buildJsonArray {
            events.forEach { event ->
                add(buildJsonObject {
                    put("kind", JsonPrimitive(event.kind.name))
                    put("summary", JsonPrimitive(event.summary))
                    put("ts", JsonPrimitive(event.timestamp))
                    put("sessionId", JsonPrimitive(event.sessionId))
                    put("agentName", JsonPrimitive(event.agentName))
                    if (event.payload.isNotEmpty()) {
                        put("payload", buildJsonObject {
                            event.payload.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        })
                    }
                })
            }
        }

        return AcpResult(true, "events_${events.size}", eventJson.toString())
    }

    // ── SESSION_DELTA: receive events from peer ─────────────────────

    /**
     * Peer sends us session events we don't have.
     * We record each event locally and notify the event bus.
     *
     * Matching OpenClaw recordSessionStateEvent — but for incoming remote events.
     */
    private suspend fun handleSessionDelta(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_payload")
        val sessionKey = payload["sessionKey"]?.jsonPrimitive?.content ?: ""
        val eventsArray = payload["events"]?.jsonArray ?: return AcpResult(false, "no_events")

        var received = 0
        for (element in eventsArray) {
            try {
                val obj = element.jsonObject
                val kindName = obj["kind"]?.jsonPrimitive?.content ?: continue
                val kind = try { SessionEventBus.EventKind.valueOf(kindName) } catch (_: Exception) { continue }
                val summary = obj["summary"]?.jsonPrimitive?.content ?: ""
                val ts = obj["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
                val payloadMap = mutableMapOf<String, String>()
                obj["payload"]?.jsonObject?.let { p ->
                    for ((k, v) in p) { payloadMap[k] = v.jsonPrimitive?.content ?: continue }
                }

                // Record the event locally (persists to event log + broadcasts on bus)
                sessionManager.recordSessionEvent(sessionKey, SessionEventBus.SessionEvent(
                    kind = kind,
                    sessionId = sessionKey,
                    agentName = agentName,
                    summary = summary,
                    payload = payloadMap,
                    timestamp = ts
                ))
                received++
            } catch (_: Exception) { /* skip malformed event */ }
        }

        return AcpResult(true, "received_$received", """{"received":$received}""")
    }

    // ── SESSION_ACK: confirm receipt ────────────────────────────────

    /**
     * Peer confirms they received our events up to [receivedSequence].
     * We record this for sync status tracking.
     */
    private suspend fun handleSessionAck(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val sessionKey = payload?.get("sessionKey")?.jsonPrimitive?.content ?: ""
        val receivedSequence = payload?.get("receivedSequence")?.jsonPrimitive?.int ?: 0

        // Log the acknowledgement
        sessionManager.recordSessionEvent(sessionKey, SessionEventBus.SessionEvent(
            kind = SessionEventBus.EventKind.SESSION_RECOVERED,
            sessionId = sessionKey,
            agentName = agentName,
            summary = "Peer acknowledged up to sequence $receivedSequence",
            payload = mapOf("peerSequence" to receivedSequence.toString(), "peerId" to msg.from)
        ))

        return AcpResult(true, "ack_recorded")
    }
}
