// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Memory ledger entry — a single block in the hash chain.
 *
 * Each entry is content-addressed via SHA-256 and linked to its predecessor,
 * forming an append-only, tamper-evident chain (like a Git commit DAG simplified
 * to a single linear chain per device).
 */
@Serializable
data class LedgerEntry(
    val id: String,
    val prevHash: String?,
    val hash: String,
    val deviceId: String,
    val deviceName: String,
    val timestamp: Long,
    val type: EntryType,
    val content: String,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    /** Pre-image of the hash: everything except [hash] itself. */
    fun preimage(): String =
        "$prevHash|$deviceId|$timestamp|${type.name}|$content|${tags.sorted().joinToString(",")}"

    companion object {
        /** Create a genesis entry (first entry in a ledger, prevHash = null). */
        fun genesis(
            deviceId: String,
            deviceName: String,
            type: EntryType = EntryType.IDENTITY_SYNC,
            content: String = "Ledger genesis for $deviceName"
        ): LedgerEntry {
            val ts = System.currentTimeMillis()
            val pre = "null|$deviceId|$ts|${type.name}|$content|"
            val h = sha256(pre)
            return LedgerEntry(
                id = "genesis-$ts",
                prevHash = null,
                hash = h,
                deviceId = deviceId,
                deviceName = deviceName,
                timestamp = ts,
                type = type,
                content = content
            )
        }

        /** Create an entry chained to [prev]. Computes hash from the full preimage. */
        fun create(
            prev: LedgerEntry,
            deviceId: String,
            deviceName: String,
            type: EntryType,
            content: String,
            tags: List<String> = emptyList(),
            metadata: Map<String, String> = emptyMap()
        ): LedgerEntry {
            val ts = System.currentTimeMillis()
            val pre = "${prev.hash}|$deviceId|$ts|${type.name}|$content|${tags.sorted().joinToString(",")}"
            val h = sha256(pre)
            val shortHash = h.take(8)
            return LedgerEntry(
                id = "mem-$ts-$shortHash",
                prevHash = prev.hash,
                hash = h,
                deviceId = deviceId,
                deviceName = deviceName,
                timestamp = ts,
                type = type,
                content = content,
                tags = tags,
                metadata = metadata
            )
        }

        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/** Types of ledger entries. */
enum class EntryType {
    MEMORY,           // Standard memory record (task result)
    DREAM,            // Dream analysis output
    SOUL_UPDATE,      // soul.md change
    PROFILE_UPDATE,   // profile.md change
    IDENTITY_SYNC,    // Twin identity declaration
    CAPABILITY_UPDATE // Device capability change
}

/**
 * Result of a ledger integrity check.
 */
data class LedgerVerification(
    val valid: Boolean,
    val entryCount: Int,
    val firstInvalidIndex: Int = -1,
    val firstInvalidReason: String = "",
    val genesisHash: String? = null,
    val latestHash: String? = null,
    val devices: Set<String> = emptySet()
)
