// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.DreamEngine
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dream-to-ledger bridge — integrates DreamEngine output with the
 * Memory Twin ledger so dream insights propagate to all twin peers.
 *
 * When a dream runs locally, the output is:
 * 1. Written to DREAM.md (as usual)
 * 2. Packaged as a LedgerEntry (type=DREAM)
 * 3. Appended to the twin ledger for sync
 *
 * When receiving a dream entry from a peer:
 * 1. The dream content is applied to the local DREAM.md
 * 2. Memory reorganization metadata is logged (tags, links, archives)
 */
object TwinDreamSync {

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /**
     * Called after a local dream pass completes.
     * Packages the dream output + metadata as a ledger entry for sync.
     *
     * @param agentName The agent that ran the dream
     * @param deviceId This device's fingerprint
     * @param deviceName Human-readable device name
     * @param dreamContent The dream output text (from DreamEngine.dreamPass)
     * @param memResult The memory reorganization result (from DreamEngine.dream)
     */
    fun onLocalDreamCompleted(
        agentName: String,
        deviceId: String,
        deviceName: String,
        dreamContent: String?,
        memResult: DreamEngine.MemResult? = null
    ) {
        if (dreamContent.isNullOrBlank() && memResult == null) return

        val content = buildString {
            if (!dreamContent.isNullOrBlank()) {
                appendLine(dreamContent)
            }
            if (memResult != null) {
                appendLine()
                appendLine("## 记忆重组")
                appendLine("- 审查: ${memResult.memoriesReviewed} 条")
                appendLine("- 标签: ${memResult.tagsAdded} 条")
                appendLine("- 链接: ${memResult.linksFound} 条")
                appendLine("- 归档: ${memResult.archived} 条")
                appendLine("- 摘要: ${memResult.summarized} 条")
            }
        }

        val latest = TwinLedgerStore.latest()
        val entry = if (latest != null) {
            LedgerEntry.create(
                prev = latest,
                deviceId = deviceId,
                deviceName = deviceName,
                type = EntryType.DREAM,
                content = content.trim(),
                tags = listOf("dream", "auto"),
                metadata = mapOf(
                    "dreamType" to "llm_pass",
                    "archivedCount" to "${memResult?.archived ?: 0}",
                    "tagsAdded" to "${memResult?.tagsAdded ?: 0}",
                    "linksFound" to "${memResult?.linksFound ?: 0}"
                )
            )
        } else {
            LedgerEntry.genesis(
                deviceId = deviceId,
                deviceName = deviceName,
                type = EntryType.DREAM,
                content = content.trim()
            )
        }

        TwinLedgerStore.append(entry)

        // Also write to twin dream archive
        try {
            val dreamDir = File(DataPaths.TWIN_DREAMS)
            if (!dreamDir.exists()) dreamDir.mkdirs()
            val dreamFile = File(dreamDir, "DREAM.md")
            val timestamp = DATE_FMT.format(Date())
            val entryText = buildString {
                if (!dreamFile.exists()) {
                    appendLine("# $agentName · 孪生梦境记录")
                }
                appendLine()
                appendLine("---")
                appendLine("## $timestamp · 本机 ($deviceName)")
                appendLine()
                appendLine(content)
                appendLine()
            }
            val existing = if (dreamFile.exists()) try { dreamFile.readText() } catch (_: Exception) { "" } else ""
            dreamFile.writeText(entryText + existing)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinDreamSync.onLocalDreamCompleted")
        }
    }

    /**
     * Get the merged dream history from all twin peers.
     *
     * @param limit Maximum number of dream entries to return
     * @return Formatted dream history string
     */
    fun getDreamHistory(limit: Int = 10): String {
        val dreamEntries = TwinLedgerStore.byType(EntryType.DREAM)
            .sortedByDescending { it.timestamp }
            .take(limit)

        if (dreamEntries.isEmpty()) return "(无梦境记录)"

        return buildString {
            appendLine("# 孪生梦境历史 (最近 $limit 条)")
            appendLine()
            dreamEntries.forEach { entry ->
                val date = DATE_FMT.format(Date(entry.timestamp))
                appendLine("## $date · 来自 ${entry.deviceName}")
                appendLine(entry.content.take(500))
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }
}
