// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Persistence layer for the memory twin ledger.
 *
 * Storage format: JSON Lines (`.jsonl`) — one [LedgerEntry] serialized as a single
 * JSON object per line. This allows O(1) append without rewriting the entire file,
 * while remaining human-readable.
 *
 * File layout:
 *   {TWIN_LEDGER}/
 *   ├── ledger.jsonl        ← primary append-only chain
 *   ├── ledger.idx          ← binary index: hash → file offset (future)
 *   └── snapshots/          ← periodic full snapshots for fast bootstrap
 *       └── 2026-07-24.jsonl
 */
object TwinLedgerStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val ledgerDir: File get() = File(DataPaths.TWIN_LEDGER)
    private val ledgerFile: File get() = File(ledgerDir, "ledger.jsonl")

    /** Ensure the store directory exists. */
    private fun ensureDir() { if (!ledgerDir.exists()) ledgerDir.mkdirs() }

    // ── P2.2: Optional encryption ──────────────────────────────────────

    /** AES key for content encryption (null = encryption disabled). */
    @Volatile private var encryptionKey: SecretKeySpec? = null

    /**
     * Enable content encryption with the given AES key.
     * Does NOT re-encrypt existing entries — only affects subsequent writes.
     */
    fun setEncryptionKey(key: ByteArray) {
        encryptionKey = SecretKeySpec(key, "AES")
    }

    /** Disable encryption. Existing encrypted entries will be unreadable. */
    fun clearEncryptionKey() {
        encryptionKey = null
    }

    /** Whether encryption is currently enabled. */
    val isEncryptionEnabled: Boolean get() = encryptionKey != null

    /** Encrypt a single field value. Returns base64(IV + ciphertext). */
    private fun encryptField(plaintext: String): String {
        val key = encryptionKey ?: return plaintext
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            java.util.Base64.getEncoder().encodeToString(iv + encrypted)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.encryptField")
            plaintext // Fallback to plaintext
        }
    }

    /** Decrypt a field value. Returns plaintext. */
    private fun decryptField(ciphertext: String): String {
        val key = encryptionKey ?: return ciphertext
        // Detect plaintext: base64-encrypted values start with a valid base64 block
        // and are always longer than the original. Simple heuristic: if it's not
        // valid base64 or too short, treat as plaintext.
        if (ciphertext.length < 24) return ciphertext
        return try {
            val combined = java.util.Base64.getDecoder().decode(ciphertext)
            if (combined.size < 17) return ciphertext
            val iv = IvParameterSpec(combined, 0, 16)
            val encrypted = combined.copyOfRange(16, combined.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { ciphertext /* Not encrypted or wrong key */ }
    }

    /** Encrypt content and metadata values of an entry (for append). */
    private fun encryptEntry(entry: LedgerEntry): LedgerEntry {
        val key = encryptionKey ?: return entry
        return entry.copy(
            content = encryptField(entry.content),
            metadata = entry.metadata.mapValues { (_, v) -> encryptField(v) }
        )
    }

    /** Decrypt content and metadata values of an entry (for read). */
    private fun decryptEntry(entry: LedgerEntry): LedgerEntry {
        val key = encryptionKey ?: return entry
        return entry.copy(
            content = decryptField(entry.content),
            metadata = entry.metadata.mapValues { (_, v) -> decryptField(v) }
        )
    }

    // ── Read ──────────────────────────────────────────────────────────

    /** Load all entries as a list. For large ledgers, prefer streaming reads. */
    fun loadAll(): List<LedgerEntry> {
        if (!ledgerFile.exists()) return emptyList()
        return try {
            ledgerFile.readLines()
                .filter { it.isNotBlank() }
                .map { json.decodeFromString<LedgerEntry>(it) }
                .let { entries ->
                    if (encryptionKey != null) entries.map { decryptEntry(it) } else entries
                }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.loadAll")
            emptyList()
        }
    }

    /** Load the last N entries (tail of the file). */
    fun loadTail(n: Int): List<LedgerEntry> {
        if (!ledgerFile.exists()) return emptyList()
        return try {
            ledgerFile.readLines().takeLast(n)
                .filter { it.isNotBlank() }
                .map { json.decodeFromString<LedgerEntry>(it) }
                .let { entries ->
                    if (encryptionKey != null) entries.map { decryptEntry(it) } else entries
                }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.loadTail")
            emptyList()
        }
    }

    /** Get the latest entry (head of chain). */
    fun latest(): LedgerEntry? {
        if (!ledgerFile.exists()) return null
        return try {
            ledgerFile.readLines().lastOrNull { it.isNotBlank() }
                ?.let { json.decodeFromString<LedgerEntry>(it) }
                ?.let { if (encryptionKey != null) decryptEntry(it) else it }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.latest")
            null
        }
    }

    /** Get entry count without loading full ledger. */
    fun count(): Int {
        if (!ledgerFile.exists()) return 0
        return try {
            ledgerFile.readLines().count { it.isNotBlank() }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.count")
            0
        }
    }

    /** Query entries by device ID. */
    fun byDevice(deviceId: String): List<LedgerEntry> =
        loadAll().filter { it.deviceId == deviceId }

    /** Query entries by type. */
    fun byType(type: EntryType): List<LedgerEntry> =
        loadAll().filter { it.type == type }

    /** Find entries since a given hash (exclusive). Returns empty if hash not found. */
    fun sinceHash(hash: String): List<LedgerEntry> {
        val all = loadAll()
        val idx = all.indexOfFirst { it.hash == hash }
        if (idx < 0) return emptyList()
        return all.drop(idx + 1)
    }

    /** Check if a hash already exists in the ledger (content dedup). */
    fun containsHash(hash: String): Boolean {
        if (!ledgerFile.exists()) return false
        return try {
            // SECURITY: Parse JSON instead of string matching to avoid false positives
            ledgerFile.readLines()
                .filter { it.isNotBlank() }
                .any { line ->
                    try {
                        val entry = json.decodeFromString<LedgerEntry>(line)
                        entry.hash == hash
                    } catch (_: Exception) { false }
                }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.containsHash")
            false
        }
    }

    // ── Write ─────────────────────────────────────────────────────────

    /**
     * Append a single entry to the ledger file.
     * Uses atomic tmp+rename to prevent corruption on crash.
     */
    @Synchronized
    fun append(entry: LedgerEntry): Boolean {
        ensureDir()
        // P2.2: Encrypt content before writing
        val encrypted = if (encryptionKey != null) encryptEntry(entry) else entry
        // Content dedup: skip if hash already exists
        if (containsHash(entry.hash)) return false

        return try {
            val line = json.encodeToString(encrypted) + "\n"
            // Atomic write: write tmp → rename
            val tmp = File(ledgerDir, "ledger.tmp")
            if (ledgerFile.exists()) {
                tmp.writeBytes(ledgerFile.readBytes())
            }
            tmp.appendText(line)
            // renameTo returns false on Windows if target exists; delete first
            if (ledgerFile.exists()) ledgerFile.delete()
            tmp.renameTo(ledgerFile)
            true
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.append")
            // Clean up tmp file on failure
            File(ledgerDir, "ledger.tmp").delete()
            false
        }
    }

    /**
     * Append multiple entries in a single atomic operation.
     * Entries that already exist (by hash) are skipped.
     * Returns the count of actually appended entries.
     */
    @Synchronized
    fun appendBatch(entries: List<LedgerEntry>): Int {
        ensureDir()
        var appended = 0
        try {
            val existing = loadAll()
            val existingHashes = existing.map { it.hash }.toSet()
            val newEntries = entries.filter { it.hash !in existingHashes }
            if (newEntries.isEmpty()) return 0
            // P2.2: Encrypt new entries before writing
            val encryptedEntries = if (encryptionKey != null) newEntries.map { encryptEntry(it) } else newEntries

            val tmp = File(ledgerDir, "ledger.tmp")
            if (ledgerFile.exists()) {
                tmp.writeBytes(ledgerFile.readBytes())
            }
            encryptedEntries.forEach { entry ->
                tmp.appendText(json.encodeToString(entry) + "\n")
                appended++
            }
            if (ledgerFile.exists()) ledgerFile.delete()
            tmp.renameTo(ledgerFile)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinLedgerStore.appendBatch")
            File(ledgerDir, "ledger.tmp").delete()
            appended = 0
        }
        return appended
    }

    // ── Verification ──────────────────────────────────────────────────

    /**
     * Verify the integrity of the entire ledger chain.
     * Checks that every entry's hash matches its preimage and that the
     * prevHash links form an unbroken chain.
     */
    fun verify(): LedgerVerification {
        val entries = loadAll()
        if (entries.isEmpty()) return LedgerVerification(true, 0,
            genesisHash = null, latestHash = null, devices = emptySet())

        val devices = mutableSetOf<String>()
        for ((i, entry) in entries.withIndex()) {
            devices.add(entry.deviceId)
            // Verify self-hash
            val expectedHash = LedgerEntry.sha256(entry.preimage())
            if (entry.hash != expectedHash) {
                return LedgerVerification(false, entries.size, i,
                    "Entry[$i] ${entry.id}: hash mismatch (expected $expectedHash, got ${entry.hash})",
                    genesisHash = entries.firstOrNull()?.hash,
                    latestHash = entries.lastOrNull()?.hash,
                    devices = devices)
            }
            // Verify chain link
            if (i > 0) {
                val prev = entries[i - 1]
                if (entry.prevHash != prev.hash) {
                    return LedgerVerification(false, entries.size, i,
                        "Entry[$i] ${entry.id}: prevHash break (expected ${prev.hash}, got ${entry.prevHash})",
                        genesisHash = entries.firstOrNull()?.hash,
                        latestHash = entries.lastOrNull()?.hash,
                        devices = devices)
                }
            }
        }
        return LedgerVerification(true, entries.size,
            genesisHash = entries.firstOrNull()?.hash,
            latestHash = entries.lastOrNull()?.hash,
            devices = devices)
    }

    // ── Statistics ────────────────────────────────────────────────────

    /** Ledger statistics for status display. */
    data class LedgerStats(
        val totalEntries: Int,
        val fileSizeBytes: Long,
        val devices: Map<String, Int>,   // deviceId → entry count
        val typeDistribution: Map<String, Int>,  // EntryType → count
        val oldestTimestamp: Long?,
        val newestTimestamp: Long?,
        val genesisHash: String?,
        val latestHash: String?,
        val verified: Boolean
    )

    fun stats(): LedgerStats {
        val entries = loadAll()
        val deviceCounts = entries.groupBy { it.deviceId }.mapValues { it.value.size }
        val typeCounts = entries.groupBy { it.type.name }.mapValues { it.value.size }
        val verification = verify()
        return LedgerStats(
            totalEntries = entries.size,
            fileSizeBytes = if (ledgerFile.exists()) ledgerFile.length() else 0,
            devices = deviceCounts,
            typeDistribution = typeCounts,
            oldestTimestamp = entries.firstOrNull()?.timestamp,
            newestTimestamp = entries.lastOrNull()?.timestamp,
            genesisHash = entries.firstOrNull()?.hash,
            latestHash = entries.lastOrNull()?.hash,
            verified = verification.valid
        )
    }
}
