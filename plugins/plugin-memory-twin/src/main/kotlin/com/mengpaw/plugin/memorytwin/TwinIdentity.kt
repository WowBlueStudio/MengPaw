// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.security.MessageDigest

/**
 * Identity document synchronizer — handles soul.md and profile.md
 * change detection and synchronization across twin peers.
 *
 * When either document changes locally, a LedgerEntry is created
 * (type=SOUL_UPDATE or PROFILE_UPDATE) and broadcast to all peers.
 *
 * When receiving an identity update from a peer, the local copy is
 * updated using LWW (Last-Write-Wins) semantics.
 */
object TwinIdentity {

    /** Snapshot of a document's hash for change detection. */
    private data class DocSnapshot(
        val path: String,
        val hash: String,
        val lastModified: Long
    )

    private val snapshots = mutableMapOf<String, DocSnapshot>()

    /**
     * Take an initial snapshot of identity documents.
     * Called at plugin startup.
     */
    fun snapshot(agentName: String) {
        listOf("soul.md", "profile.md").forEach { docName ->
            val file = File(DataPaths.AGENTS, "$agentName/$docName")
            if (file.exists()) {
                val content = try { file.readText() } catch (_: Exception) { return@forEach }
                snapshots[docName] = DocSnapshot(
                    path = file.absolutePath,
                    hash = sha256(content),
                    lastModified = file.lastModified()
                )
            }
        }
    }

    /**
     * Check all identity documents for changes since last snapshot.
     * Returns a list of [LedgerEntry] for any changed documents.
     */
    fun checkForChanges(
        agentName: String,
        deviceId: String,
        deviceName: String
    ): List<LedgerEntry> {
        val entries = mutableListOf<LedgerEntry>()
        val types = mapOf("soul.md" to EntryType.SOUL_UPDATE, "profile.md" to EntryType.PROFILE_UPDATE)

        types.forEach { (docName, entryType) ->
            val file = File(DataPaths.AGENTS, "$agentName/$docName")
            if (!file.exists()) return@forEach

            val content = try { file.readText() } catch (_: Exception) { return@forEach }
            val currentHash = sha256(content)
            val snapshot = snapshots[docName]

            if (snapshot == null || snapshot.hash != currentHash) {
                // Document changed — create ledger entry
                val latest = TwinLedgerStore.latest()
                val entry = if (latest != null) {
                    LedgerEntry.create(
                        prev = latest,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        type = entryType,
                        content = content,
                        tags = listOf(docName.removeSuffix(".md"), "identity")
                    )
                } else {
                    LedgerEntry.genesis(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        type = entryType,
                        content = content
                    )
                }
                entries.add(entry)

                // Update snapshot
                snapshots[docName] = DocSnapshot(
                    path = file.absolutePath,
                    hash = currentHash,
                    lastModified = file.lastModified()
                )
            }
        }
        return entries
    }

    /**
     * Push identity documents to a peer by creating ledger entries.
     * Returns the ledger entries for the current identity documents.
     */
    fun pushIdentityDocs(
        agentName: String,
        deviceId: String,
        deviceName: String
    ): List<LedgerEntry> {
        val entries = mutableListOf<LedgerEntry>()

        listOf("soul.md" to EntryType.SOUL_UPDATE, "profile.md" to EntryType.PROFILE_UPDATE)
            .forEach { (docName, entryType) ->
                val file = File(DataPaths.AGENTS, "$agentName/$docName")
                if (!file.exists()) return@forEach

                val content = try { file.readText() } catch (_: Exception) { return@forEach }
                val latest = TwinLedgerStore.latest()
                val entry = if (latest != null) {
                    LedgerEntry.create(latest, deviceId, deviceName, entryType, content,
                        tags = listOf(docName.removeSuffix(".md"), "identity", "push"))
                } else {
                    LedgerEntry.genesis(deviceId, deviceName, entryType, content)
                }
                entries.add(entry)
                TwinLedgerStore.append(entry)
            }

        return entries
    }

    /**
     * Generate a diff between local and peer identity documents.
     * Returns a human-readable comparison.
     */
    fun diffIdentityDocs(
        agentName: String,
        peerCard: String?
    ): String {
        if (peerCard == null) return "(无法获取对端身份信息)"

        return buildString {
            appendLine("## 身份文档对比")
            appendLine()
            appendLine("### 本机")
            listOf("soul.md", "profile.md").forEach { docName ->
                val file = File(DataPaths.AGENTS, "$agentName/$docName")
                if (file.exists()) {
                    val hash = sha256(file.readText()).take(12)
                    val size = file.length()
                    val mod = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(file.lastModified()))
                    appendLine("- **$docName**: $hash... (${size}B, $mod)")
                }
            }
            appendLine()
            appendLine("### 对端")
            appendLine(peerCard)
            appendLine()
            appendLine("> 精确差异需在配对后通过账本同步自动合并")
        }
    }

    /** SHA-256 hash helper. */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
