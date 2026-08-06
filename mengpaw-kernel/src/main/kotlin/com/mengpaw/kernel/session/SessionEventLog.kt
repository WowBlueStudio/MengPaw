// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.KernelLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * 会话事件持久化职责（自 SessionManager 拆出 — 400 行文件拆分批次 1）。
 *
 * Durable Session Event Log (matching OpenClaw session_state_events table):
 * 事件写入 {agentName}/sessions/{sessionId}.event.log (JSONL) + 内存 SessionEventBus 广播。
 * 行号天然作为自增序号 (line 1 = seq 1 — matching OpenClaw INTEGER PRIMARY KEY AUTOINCREMENT)。
 *
 * 同步契约: 与 [lock] 共用 — 事件追加与 SessionManager 消息变更监视器一致。
 */
internal class SessionEventLog(
    private val lock: Any,
    private val dialogDirProvider: () -> String,
    private val agentNameProvider: () -> String
) {
    /**
     * Record a session lifecycle event to both the in-memory bus and the durable JSONL log.
     *
     * Architecture (matching OpenClaw recordSessionStateEvent):
     *   1. Append to {agentName}/sessions/{sessionId}.event.log (JSONL, one line per event)
     *   2. Emit to SessionEventBus (in-memory, for subscribers)
     */
    fun recordSessionEvent(sessionId: String, event: SessionEventBus.SessionEvent) {
        // 1. Durable write to event log
        try {
            val dir = File(dialogDirProvider()).also { it.mkdirs() }
            val logFile = File(dir, "${sessionId}.event.log")
            val logLine = buildJsonObject {
                put("kind", event.kind.name)
                put("ts", event.timestamp)
                put("summary", event.summary)
                if (event.payload.isNotEmpty()) {
                    putJsonObject("payload") {
                        event.payload.forEach { (k, v) -> put(k, v) }
                    }
                }
            }.toString()
            java.io.FileWriter(logFile, true).use { fw ->
                fw.write(logLine + "\n")
                fw.flush()
            }
        } catch (e: Exception) {
            KernelLog.w("History", "recordSessionEvent: ${e.message}")
        }
        // 2. In-memory broadcast
        SessionEventBus.emit(event)
    }

    /**
     * List all session events that occurred after the given sequence number.
     * Sequence numbers correspond to 1-indexed lines in the event log.
     *
     * Matching OpenClaw listSessionStateEventsSince(sessionKey, agentId, afterSequence, limit).
     *
     * @return list of events, newest first; empty list if log is missing or corrupted.
     */
    fun listEventsSince(sessionId: String, afterSeq: Int = 0, limit: Int = 50): List<SessionEventBus.SessionEvent> {
        try {
            val logFile = File(dialogDirProvider(), "${sessionId}.event.log")
            if (!logFile.exists()) return emptyList()

            return logFile.useLines { lines ->
                lines.drop(afterSeq).take(limit).mapNotNull { line ->
                    try {
                        val root = Json.parseToJsonElement(line).jsonObject
                        val kindName = root["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val kind = try { SessionEventBus.EventKind.valueOf(kindName) } catch (_: Exception) { return@mapNotNull null }
                        val summary = root["summary"]?.jsonPrimitive?.content ?: ""
                        val ts = root["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        val payload = mutableMapOf<String, String>()
                        root["payload"]?.jsonObject?.let { payloadObj ->
                            for ((k, v) in payloadObj) {
                                try { payload[k] = v.jsonPrimitive.content } catch (_: Exception) { }
                            }
                        }
                        SessionEventBus.SessionEvent(
                            kind = kind,
                            sessionId = sessionId,
                            agentName = agentNameProvider(),
                            summary = summary,
                            payload = payload,
                            timestamp = ts
                        )
                    } catch (_: Exception) { null }
                }.toList()
            }
        } catch (e: Exception) {
            KernelLog.w("History", "listEventsSince: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Prune old session events from the JSONL log.
     * Removes events older than [maxAgeDays] and keeps at most [maxLines] most recent lines.
     * Called periodically (e.g. during compression or at startup) to prevent unbounded growth.
     *
     * Matching OpenClaw pruneSessionStateEvents() — 30 day / 50000 row policy.
     */
    fun pruneSessionEvents(sessionId: String, maxAgeDays: Int = 30, maxLines: Int = 5000) {
        synchronized(lock) {
            try {
                val logFile = File(dialogDirProvider(), "${sessionId}.event.log")
                if (!logFile.exists() || logFile.length() == 0L) return

                val lines = logFile.useLines { it.toList() }
                if (lines.size <= maxLines) return  // still under limit

                val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600 * 1000L
                val pruned = lines.filter { line ->
                    try {
                        val root = Json.parseToJsonElement(line).jsonObject
                        val ts = root["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        ts >= cutoff
                    } catch (_: Exception) { true }  // keep unparseable lines
                }.takeLast(maxLines)

                if (pruned.size < lines.size) {
                    logFile.atomicWriteText(pruned.joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
                KernelLog.w("History", "pruneSessionEvents: ${e.message}")
            }
        }
    }
}

/**
 * 标准原子写: 先写同目录 `.tmp`，再以 Files.move(REPLACE_EXISTING) 覆盖目标。
 * POSIX(Android/Linux) 上等价于 rename(2)，原子替换且失败时原文件完好；
 * Windows 上 File.renameTo 无法覆盖已存在目标(实测返回 false 且不动原文件)，
 * 而 Files.move 可替换 —— 任何失败路径都不会先删原文件, 不会丢数据。
 */
private fun File.atomicWriteText(text: String) {
    val tmp = File(this.parentFile, "${this.name}.tmp")
    try {
        tmp.writeText(text)
        java.nio.file.Files.move(
            tmp.toPath(), this.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    } catch (e: Exception) {
        // 失败时原文件保持完好，仅清理残留 tmp 后向上抛, 由调用方上报
        try { tmp.delete() } catch (_: Exception) {}
        throw e
    }
}
