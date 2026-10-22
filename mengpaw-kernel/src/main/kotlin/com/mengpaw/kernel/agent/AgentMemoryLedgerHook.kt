// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.memory.MemoryLedger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆写入元数据 — 命令层从 [com.mengpaw.kernel.cli.ExecutionContext] 提取,
 * 由 [AgentDocs] 写入 API 透传给账本挂钩 (session/device/project)。
 */
data class MemoryWriteMeta(
    val session: String = "",
    val device: String = "local",
    val project: String = ""
)

/**
 * 记忆写入 → 账本挂钩 (P3, docs/memory-git-sync-design.md §6.2)。
 *
 * 现有命令零新增: `agent.memory.record` / `project.save` 落盘后在这里生成条目;
 * `mid.edit` / `project.edit` / `mid.rm` 改为**追加 revision 块**, 不物理覆盖历史块。
 */
internal object AgentMemoryLedgerHook {

    private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val STAMP_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    data class RevisionOutcome(val entryId: String, val header: String, val ledgerError: String? = null)

    /** `flushMidTermMemoryQueue` 落盘成功后调用 — 条目正文 = 文件里那块 (`## HH:mm:ss …`)。 */
    fun onMidWritten(agentName: String, date: String, timestamp: String, body: String, meta: MemoryWriteMeta) {
        try {
            val block = "## $timestamp\n\n$body"
            MemoryLedger.appendNote(
                agentName = agentName,
                track = MemoryLedger.TRACK_MID,
                file = "memory/memory_$date.md",
                block = block,
                project = meta.project,
                device = meta.device,
                session = meta.session
            )
        } catch (e: Exception) {
            ErrorCollector.report(e, "AgentMemoryLedgerHook.onMidWritten")
        }
    }

    /** `saveProjectMemory` 落盘成功后调用。 */
    fun onProjectWritten(agentName: String, projectName: String, timestamp: String, report: String, meta: MemoryWriteMeta) {
        try {
            val block = "## $timestamp · 里程碑总结\n\n$report\n---"
            MemoryLedger.appendNote(
                agentName = agentName,
                track = MemoryLedger.TRACK_PROJECT,
                file = "memory/project_${projectName}_memory.md",
                block = block,
                project = projectName.ifBlank { meta.project },
                device = meta.device,
                session = meta.session
            )
        } catch (e: Exception) {
            ErrorCollector.report(e, "AgentMemoryLedgerHook.onProjectWritten")
        }
    }

    /**
     * 追加 revision / tombstone 块 (不覆盖原块)。
     *
     * @param revoked true = 撤回 (tombstone 正文), false = 修订为新内容
     * @return null = 原条目不存在/匹配不唯一/IO 失败; 否则新 revision 条目信息
     */
    fun appendRevision(
        agentName: String,
        filePath: String,
        originalId: String,
        newContent: String,
        track: String,
        project: String,
        meta: MemoryWriteMeta,
        revoked: Boolean
    ): RevisionOutcome? {
        if (originalId.isBlank()) return null
        val file = File(filePath)
        if (!file.isFile) return null
        return try {
            val text = file.readText()
            val matched = MemoryLedger.splitBlocks(text).filter {
                MemoryLedger.titleOf(it).startsWith(originalId)
            }
            if (matched.size != 1) return null
            val originalHash = MemoryLedger.blockHash(matched[0])
            val now = Date()
            val title = if (revoked) "撤回" else "修订"
            val stamp = if (track == MemoryLedger.TRACK_MID) TIME_FMT.format(now) else STAMP_FMT.format(now)
            val body = if (revoked) {
                "> 已撤回（原条目保留，账本记 revision 条目）。"
            } else {
                newContent
            }
            val newBlock = "## $title $stamp · 原 $originalId\n\n$body"
            val updated = text.trimEnd() + "\n\n" + newBlock + "\n"
            if (!writeAtomic(file, updated)) return null
            val result = MemoryLedger.appendNote(
                agentName = agentName,
                track = track,
                file = MemoryLedger.relativePath(agentName, filePath),
                block = newBlock,
                project = project,
                device = meta.device,
                session = meta.session,
                kind = MemoryLedger.KIND_REVISION,
                supersedes = listOf(originalHash)
            )
            if (result.error != null) ErrorCollector.report(
                java.io.IOException("revision 落账本失败: ${result.error}"),
                "AgentMemoryLedgerHook.appendRevision"
            )
            try {
                com.mengpaw.kernel.memory.MemoryIndexWriter.rebuild(agentName)
            } catch (e: Exception) {
                ErrorCollector.report(e, "AgentMemoryLedgerHook.indexRebuild")
            }
            RevisionOutcome(result.entryId, MemoryLedger.titleOf(newBlock), result.error)        } catch (e: Exception) {
            ErrorCollector.report(e, "AgentMemoryLedgerHook.appendRevision")
            null
        }
    }

    /** 原子写 (tmp + Files.move 覆盖), 失败不动原文件。 */
    private fun writeAtomic(file: File, content: String): Boolean {
        val tmp = File(file.parentFile, "${file.name}.revision.tmp")
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(content)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            ErrorCollector.report(e, "AgentMemoryLedgerHook.writeAtomic")
            false
        } finally {
            if (tmp.exists()) {
                try { tmp.delete() } catch (_: Exception) { /* best-effort */ }
            }
        }
    }
}
