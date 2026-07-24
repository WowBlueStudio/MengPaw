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
        AcpMessageType.TWIN_DELEGATE,
        AcpMessageType.PAIR_CHALLENGE,
        AcpMessageType.PAIR_CONFIRM
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
                AcpMessageType.PAIR_CHALLENGE -> handlePairChallenge(message)
                AcpMessageType.PAIR_CONFIRM -> handlePairConfirm(message)
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

        // Verify chain integrity of received entries (internal)
        for (i in 1 until entries.size) {
            val expectedHash = LedgerEntry.sha256(entries[i].preimage())
            if (entries[i].hash != expectedHash) {
                return AcpResult(false, "entry_${i}_hash_mismatch")
            }
            if (entries[i].prevHash != entries[i - 1].hash) {
                return AcpResult(false, "entry_${i}_chain_break")
            }
        }

        // P0 FIX: Verify cross-chain continuity — first entry must link to our local chain
        val localLatest = TwinLedgerStore.latest()
        if (localLatest != null) {
            val firstPrevHash = entries[0].prevHash
            if (firstPrevHash != null && firstPrevHash != localLatest.hash) {
                return AcpResult(false, "chain_fork",
                    "Cross-chain discontinuity: batch starts at $firstPrevHash but local head is ${localLatest.hash}")
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
        // Parse payload to extract nonce and capability card
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val capabilityCard = payload?.get("capabilityCard")?.jsonPrimitive?.content ?: msg.payload
        val nonce = payload?.get("nonce")?.jsonPrimitive?.content ?: ""

        // If this is a pairing request (has nonce), use pairing engine
        if (nonce.isNotBlank()) {
            val transport = syncEngine.getTransport()
            val deviceId = MemoryTwinPlugin.appContext?.let {
                try { AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
            } ?: "device-unknown"
            val myFingerprint = try { AcpCrypto.myFingerprint() } catch (_: Exception) { deviceId }

            if (transport != null) {
                TwinPairingEngine.handleAnnounce(msg.from, nonce, deviceId, myFingerprint, transport)
                return AcpResult(true, "pairing_challenge_sent")
            }
        }

        // Legacy: still write to inbox for backward compatibility
        syncEngine.onCapabilityReceived(msg.from, capabilityCard)
        return AcpResult(true, "capability_stored")
    }

    private suspend fun handleTwinDelegate(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val task = payload?.get("task")?.jsonPrimitive?.content ?: ""
        val requirementsStr = payload?.get("requirements")?.jsonPrimitive?.content ?: "[]"
        // SECURITY: Only accept delegate tasks from trusted peers
        if (!com.mengpaw.kernel.security.PromptFirewall.isTrusted(msg.from)) {
            return AcpResult(false, "untrusted_delegate",
                "Task delegation requires paired trust. Complete twin pairing first.")
        }
        syncEngine.onTwinDelegateReceived(msg.from, task, requirementsStr)
        return AcpResult(true, "delegate_queued")
    }

    // ── Pairing protocol handlers ───────────────────────────────────

    private suspend fun handlePairChallenge(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val deviceId = payload?.get("deviceId")?.jsonPrimitive?.content ?: return AcpResult(false, "no_device_id")
        val nonceB = payload?.get("nonceB")?.jsonPrimitive?.content ?: return AcpResult(false, "no_nonce")
        val peerFingerprint = payload?.get("fingerprint")?.jsonPrimitive?.content ?: ""

        // Forward to pairing engine (initiator side)
        val result = TwinPairingEngine.handleChallenge(deviceId, nonceB, peerFingerprint)
        if (result.error.isNotBlank()) {
            return AcpResult(false, "pair_challenge_failed", result.error)
        }
        return AcpResult(true, "challenge_received", result.verificationCode)
    }

    private suspend fun handlePairConfirm(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val deviceId = payload?.get("deviceId")?.jsonPrimitive?.content ?: return AcpResult(false, "no_device_id")
        val verificationCode = payload?.get("verificationCode")?.jsonPrimitive?.content ?: ""
        val signature = payload?.get("signature")?.jsonPrimitive?.content ?: ""

        // Forward to pairing engine (responder side)
        val result = TwinPairingEngine.handleConfirm(deviceId, verificationCode, signature)
        if (result.error.isNotBlank()) {
            return AcpResult(false, "pair_confirm_failed", result.error)
        }
        // After successful pairing, update sync engine with new peer
        syncEngine.onPairingEstablished(deviceId)
        return AcpResult(true, "pairing_established")
    }
}
