// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.acp.*
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.*

/**
 * ACP message handler for Memory Twin protocol messages.
 *
 * This is the first concrete implementation of the [AcpHandler] interface
 * defined in the kernel. It handles all 6 Memory Twin message types and
 * delegates business logic to [TwinSyncEngine].
 */
class TwinAcpHandler(
    private val syncEngine: TwinSyncEngine
) : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(
        AcpMessageType.LEDGER_HEAD,
        AcpMessageType.LEDGER_PULL,
        AcpMessageType.LEDGER_BATCH,
        AcpMessageType.LEDGER_ACK,
        AcpMessageType.CAPABILITY_ANNOUNCE,
        AcpMessageType.TWIN_DELEGATE
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        val type = try {
            AcpMessageType.valueOf(message.type)
        } catch (_: IllegalArgumentException) {
            return null // Not a twin message
        }

        if (type !in supportedTypes) return null

        return try {
            when (type) {
                AcpMessageType.LEDGER_HEAD -> handleLedgerHead(message, server)
                AcpMessageType.LEDGER_PULL -> handleLedgerPull(message, server)
                AcpMessageType.LEDGER_BATCH -> handleLedgerBatch(message, server)
                AcpMessageType.LEDGER_ACK -> handleLedgerAck(message)
                AcpMessageType.CAPABILITY_ANNOUNCE -> handleCapabilityAnnounce(message)
                AcpMessageType.TWIN_DELEGATE -> handleTwinDelegate(message)
                else -> null
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinAcpHandler.handle(${message.type})")
            AcpResult(false, "Handler error: ${e.message}")
        }
    }

    // ── Message handlers ───────────────────────────────────────────

    private suspend fun handleLedgerHead(msg: AcpMessage, server: AcpServer): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val latestHash = payload?.get("latestHash")?.jsonPrimitive?.content ?: ""
        val entryCount = payload?.get("entryCount")?.jsonPrimitive?.int ?: 0

        val localLatest = TwinLedgerStore.latest()
        val localCount = TwinLedgerStore.count()
        val localHash = localLatest?.hash ?: ""

        return if (localHash == latestHash) {
            // Already in sync
            AcpResult(true, "in_sync", localHash)
        } else if (localCount > entryCount) {
            // We have more entries — tell them to pull from us
            AcpResult(true, "ahead", buildJsonObject {
                put("myHash", localHash)
                put("myCount", localCount)
            }.toString())
        } else {
            // They have more entries — we should pull
            AcpResult(true, "behind", buildJsonObject {
                put("myHash", localHash)
                put("myCount", localCount)
            }.toString())
        }
    }

    private suspend fun handleLedgerPull(msg: AcpMessage, server: AcpServer): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val sinceHash = payload?.get("sinceHash")?.jsonPrimitive?.content ?: ""
        val maxCount = payload?.get("maxCount")?.jsonPrimitive?.int ?: 100

        val entries = if (sinceHash.isEmpty() || sinceHash == "null") {
            TwinLedgerStore.loadAll().take(maxCount)
        } else {
            TwinLedgerStore.sinceHash(sinceHash).take(maxCount)
        }

        if (entries.isEmpty()) {
            return AcpResult(true, "no_new_entries", "[]")
        }

        // Respond with the entries as a LEDGER_BATCH
        val entriesArray = entries.joinToString(",", "[", "]") {
            com.mengpaw.kernel.acp.AcpMessage.ledgerBatch(it.deviceId, msg.from, "[]", it.hash, it.hash).let { _ -> "" }
        }

        // Rebuild entries into JSON array for the response
        val entryJsonList = buildJsonArray {
            entries.forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.id)
                    put("prevHash", entry.prevHash ?: "null")
                    put("hash", entry.hash)
                    put("deviceId", entry.deviceId)
                    put("deviceName", entry.deviceName)
                    put("timestamp", entry.timestamp)
                    put("type", entry.type.name)
                    put("content", entry.content)
                    put("tags", buildJsonArray { entry.tags.forEach { add(it) } })
                    put("metadata", buildJsonObject {
                        entry.metadata.forEach { (k, v) -> put(k, v) }
                    })
                })
            }
        }

        val rangeStart = entries.first().hash
        val rangeEnd = entries.last().hash

        // Send response as LEDGER_BATCH via server
        val batchMsg = AcpMessage.ledgerBatch(
            msg.from, msg.to, entryJsonList.toString(), rangeStart, rangeEnd
        )
        // server.send would be called by caller; we return the data

        return AcpResult(true, "entries_${entries.size}", entryJsonList.toString())
    }

    private suspend fun handleLedgerBatch(msg: AcpMessage, server: AcpServer): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_payload")
        val entriesArray = payload["entries"]?.jsonArray ?: return AcpResult(false, "no_entries")

        val entries = entriesArray.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                LedgerEntry(
                    id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    prevHash = obj["prevHash"]?.jsonPrimitive?.content?.let { if (it == "null") null else it },
                    hash = obj["hash"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: "",
                    deviceName = obj["deviceName"]?.jsonPrimitive?.content ?: "",
                    timestamp = obj["timestamp"]?.jsonPrimitive?.long ?: 0L,
                    type = try { EntryType.valueOf(obj["type"]?.jsonPrimitive?.content ?: "MEMORY") } catch (_: Exception) { EntryType.MEMORY },
                    content = obj["content"]?.jsonPrimitive?.content ?: "",
                    tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content } ?: emptyList(),
                    metadata = obj["metadata"]?.jsonObject?.entries?.associate { (k, v) -> k to (v.jsonPrimitive?.content ?: v.toString()) } ?: emptyMap()
                )
            } catch (e: Exception) {
                ErrorCollector.report(e, "TwinAcpHandler.parseEntry")
                null
            }
        }

        if (entries.isEmpty()) return AcpResult(false, "no_valid_entries")

        // Verify chain integrity of received entries
        for (i in 1 until entries.size) {
            val expectedHash = LedgerEntry.sha256(entries[i].preimage())
            if (entries[i].hash != expectedHash) {
                return AcpResult(false, "entry_${i}_hash_mismatch")
            }
            if (entries[i].prevHash != entries[i - 1].hash) {
                return AcpResult(false, "entry_${i}_chain_break")
            }
        }

        // Merge into local ledger
        val appended = TwinLedgerStore.appendBatch(entries)

        // Trigger post-sync hooks
        syncEngine.onEntriesReceived(entries)

        return AcpResult(true, "merged_$appended", entries.lastOrNull()?.hash ?: "")
    }

    private suspend fun handleLedgerAck(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val receivedHash = payload?.get("receivedHash")?.jsonPrimitive?.content ?: ""
        syncEngine.onAckReceived(msg.from, receivedHash)
        return AcpResult(true, "ack_received", receivedHash)
    }

    private suspend fun handleCapabilityAnnounce(msg: AcpMessage): AcpResult {
        syncEngine.onCapabilityReceived(msg.from, msg.payload)
        return AcpResult(true, "capability_stored")
    }

    private suspend fun handleTwinDelegate(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val task = payload?.get("task")?.jsonPrimitive?.content ?: ""
        val requirementsStr = payload?.get("requirements")?.jsonPrimitive?.content ?: "[]"
        syncEngine.onTwinDelegateReceived(msg.from, task, requirementsStr)
        return AcpResult(true, "delegate_queued")
    }
}
