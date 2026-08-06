// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Agent 三轨记忆实现 — 拆自 AgentDocs (400 行文件拆分)。
 * 长期 (memory.md, 注入提示词) / 中期 (memory_{date}.md, 批量刷盘队列) /
 * 项目 (project_*_memory.md) + 单条目增删改 + 文件级删除 + 旧 API 兼容。
 */
internal class AgentDocsMemory {

    /**
     * 旧模板教学章节黑名单 — 判定"memory.md 仍是原样旧模板"用。
     * 真实记忆的 `## ` 标题是时间戳 (如 "2026-08-05 14:30"), 天然不在黑名单,
     * 因此"全部标题命中黑名单" ⇔ 文件从未被写入真实记忆, 永不误判迁移。
     */
    internal val TEMPLATE_HEADING_BLACKLIST = setOf(
        // zh 旧模板 (v0.30.0 前) 教学章节
        "这个文件是什么", "这里记什么", "示例", "怎么写入（用命令，别直接编辑文件）", "不记什么",
        // en 旧模板教学章节
        "What this file is", "What to record here", "Example",
        "How to write (use commands, don't edit files directly)", "What NOT to record"
    )

    // ── Long-term memory (injected into system prompt) ────────────

    /** Read long-term memory — injected into every LLM system prompt. */
    internal suspend fun readLongTermMemoryAsync(agentName: String): String = withContext(com.mengpaw.kernel.KernelDispatchers.PROMPT_IO) {
        readLongTermMemory(agentName)
    }

    internal fun readLongTermMemory(agentName: String): String {
        val file = File(DataPaths.longTermMemoryFile(agentName))
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readLongTermMemory"); ""
        } else ""
    }

    /**
     * 长期记忆条目数 — 排除旧模板教学章节 (FIX(自检报告 P1-4): 此前裸数 ## 行,
     * 旧模板 5 个教学章节被数成"5 条记忆")。新瘦身模板无 ## 标题, 自然为 0。
     */
    internal fun countLongTermEntries(agentName: String): Int =
        readLongTermMemory(agentName).lines().count { l ->
            l.startsWith("## ") && l.removePrefix("## ").trim() !in TEMPLATE_HEADING_BLACKLIST
        }

    /**
     * Append to long-term memory — only for curated content.
     * Three valid sources:
     *   1. User says "请你记住"
     *   2. Agent self-judges as important/reusable
     *   3. Dream mode reorganization output
     */
    internal fun appendLongTermMemory(agentName: String, entry: String) =
        appendLongTermMemory(agentName, entry, defaultMemoryTimestamp())

    /** 指定标题的长期记忆写入 (供 agent.memory.write 使用, 标题=ID)。 */
    internal fun appendLongTermMemory(agentName: String, entry: String, title: String) {
        try {
            val file = File(DataPaths.longTermMemoryFile(agentName))
            file.parentFile?.mkdirs()
            val line = "\n## $title\n\n$entry\n"
            val existing = if (file.exists()) try { file.readText() } catch (e: Exception) { KernelLog.w("AgentDocs", "readExisting: ${e.message}"); "" } else ""
            val tmp = File(file.parentFile, "memory.tmp")
            tmp.writeText(existing + line)
            // 标准原子写: 覆盖式移动, 失败保留原文件 (旧写法先删后搬, rename 失败即丢记忆)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (e: Exception) { KernelLog.w("AgentDocs", "tmpCleanup: ${e.message}") } }
            }
            AgentDocs.notifyDocChanged(agentName, file.absolutePath)
        } catch (e: Exception) { KernelLog.w("AgentDocs", "tmpCleanup2: ${e.message}") }
    }

    private fun defaultMemoryTimestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    /** Search long-term memory by keywords. */
    internal fun searchLongTermMemory(agentName: String, keywords: List<String>): String {
        val file = File(DataPaths.longTermMemoryFile(agentName))
        if (!file.exists() || keywords.isEmpty()) return ""
        val content = try { file.readText() } catch (e: Exception) { KernelLog.w("AgentDocs", "searchLongTermMemory: ${e.message}"); return "" }
        if (content.isBlank()) return ""
        val entries = content.split(Regex("(?=## )")).filter { it.isNotBlank() }
        val matched = entries.filter { entry ->
            keywords.any { kw -> entry.contains(kw, ignoreCase = true) }
        }
        return if (matched.isEmpty()) ""
        else "## 长期记忆\n\n${matched.joinToString("\n").trim()}"
    }

    // ── Mid-term memory (dated files, auto-recording, not in prompt) ──

    /** 中期记忆写入队列 — 批量刷盘, 利用 LLM 等待窗口消除 I/O 延迟 */
    private data class QueuedWrite(val agentName: String, val line: String)
    private val midTermQueue = ConcurrentLinkedQueue<QueuedWrite>()

    /** Today's date for mid-term file naming. */
    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    internal suspend fun readMidTermMemoryAsync(agentName: String): String = withContext(com.mengpaw.kernel.KernelDispatchers.PROMPT_IO) {
        readMidTermMemory(agentName)
    }

    /** Read all mid-term memory files for today (for dream processing). */
    internal fun readMidTermMemory(agentName: String): String = readMidTermMemoryDate(agentName, today())

    /** Read mid-term memory for a specific date. */
    internal fun readMidTermMemoryDate(agentName: String, date: String): String {
        val file = File(DataPaths.midTermMemoryFile(agentName, date))
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readMidTermMemory"); ""
        } else ""
    }

    /** List all mid-term memory date files, sorted newest first. */
    internal fun listMidTermDates(agentName: String): List<String> {
        val dir = File(DataPaths.midTermMemoryDir(agentName))
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("memory_") && it.name.endsWith(".md") && it.name != "memory.md" }
            ?.map { it.name.removePrefix("memory_").removeSuffix(".md") }
            ?.sortedDescending()
            ?: emptyList()
    }

    /**
     * 将中期记忆条目加入写入队列 (立即返回, 不阻塞).
     * 实际落盘由 flushMidTermMemoryQueue() 在 LLM 响应返回后执行,
     * 利用 2-5 秒网络等待窗口消除 I/O 感知延迟.
     */
    internal fun appendMidTermMemory(agentName: String, entry: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "\n## $timestamp\n\n$entry\n"
        midTermQueue.add(QueuedWrite(agentName, line))
        // 中期记忆不注入系统提示词, 不需要触发缓存失效
    }

    /**
     * 刷新中期记忆写入队列到磁盘.
     * 在 LLM 响应返回后调用, 利用已有等待窗口使 I/O 成本为零.
     * @return 成功落盘的条目数
     */
    internal fun flushMidTermMemoryQueue(): Int {
        if (midTermQueue.isEmpty()) return 0
        // 按 agentName 分组, 合并每个 agent 的写入
        val grouped = mutableMapOf<String, StringBuilder>()
        var count = 0
        while (true) {
            val item = midTermQueue.poll() ?: break
            grouped.getOrPut(item.agentName) { StringBuilder() }.append(item.line)
            count++
        }
        if (grouped.isEmpty()) return 0
        for ((agent, lines) in grouped) {
            try {
                val file = File(DataPaths.midTermMemoryFile(agent, today()))
                file.parentFile?.mkdirs()
                val existing = if (file.exists()) try { file.readText() } catch (e: Exception) { KernelLog.w("AgentDocs", "readExisting: ${e.message}"); "" } else ""
                val tmp = File(file.parentFile, "memory.tmp")
                tmp.writeText(existing + lines.toString())
                // 标准原子写: Files.move 覆盖 (renameTo 在 Windows 上无法覆盖已存在目标)
                try {
                    java.nio.file.Files.move(
                        tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } finally {
                    if (tmp.exists()) { try { tmp.delete() } catch (e: Exception) { KernelLog.w("AgentDocs", "tmpCleanup: ${e.message}") } }
                }
            } catch (e: Exception) { KernelLog.w("AgentDocs", "tmpCleanup2: ${e.message}") }
        }
        return count
    }

    /** Search across all mid-term memory files by keywords. */
    internal fun searchMidTermMemory(agentName: String, keywords: List<String>): String {
        val dir = File(DataPaths.midTermMemoryDir(agentName))
        if (!dir.exists() || keywords.isEmpty()) return ""
        val allMatched = mutableListOf<String>()
        dir.listFiles()?.filter { it.name.startsWith("memory_") && it.name.endsWith(".md") && it.name != "memory.md" }
            ?.sortedByDescending { it.name }?.forEach { file ->
                val content = try { file.readText() } catch (e: Exception) { KernelLog.w("AgentDocs", "searchMidTermMemory: ${e.message}"); "" }
                if (content.isBlank()) return@forEach
                val entries = content.split(Regex("(?=## )")).filter { it.isNotBlank() }
                entries.filter { entry ->
                    keywords.any { kw -> entry.contains(kw, ignoreCase = true) }
                }.forEach { allMatched.add(it) }
            }
        return if (allMatched.isEmpty()) ""
        else "## 相关中期记忆 (${allMatched.size} 条)\n\n${allMatched.joinToString("\n").trim()}"
    }

    /** Get mid-term memory stats: date → entry count. */
    internal fun midTermStats(agentName: String): Map<String, Int> {
        val dir = File(DataPaths.midTermMemoryDir(agentName))
        if (!dir.exists()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        dir.listFiles()?.filter { it.name.startsWith("memory_") && it.name.endsWith(".md") && it.name != "memory.md" }
            ?.forEach { file ->
                val date = file.name.removePrefix("memory_").removeSuffix(".md")
                val count = try { file.readLines().count { it.startsWith("## ") } } catch (e: Exception) { KernelLog.w("AgentDocs", "midTermStats: ${e.message}"); 0 }
                result[date] = count
            }
        return result.toList().sortedByDescending { it.first }.toMap()
    }

    // ── Project memory (reusable project completion patterns) ──────

    /**
     * Save a project completion report.
     * Called at milestones or project closure to capture reusable methodology.
     */
    internal fun saveProjectMemory(agentName: String, projectName: String, report: String) {
        try {
            val file = File(DataPaths.projectMemoryFile(agentName, projectName))
            file.parentFile?.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.getDefault()).format(java.util.Date())
            val header = buildString {
                if (!file.exists()) {
                    appendLine("# 项目记忆: $projectName")
                    appendLine("> 创建: $timestamp")
                    appendLine("> 类型: 可复用项目完成模式")
                    appendLine()
                }
            }
            val entry = "\n## $timestamp · 里程碑总结\n\n$report\n---\n"
            val existing = if (file.exists()) try { file.readText() } catch (e: Exception) { KernelLog.w("AgentDocs", "saveProjectMemory.read: ${e.message}"); "" } else header
            val tmp = File(file.parentFile, "project.tmp")
            tmp.writeText(existing + entry)
            // 标准原子写: Files.move 覆盖 (renameTo 在 Windows 上无法覆盖已存在目标)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (e: Exception) { KernelLog.w("AgentDocs", "tmpCleanup: ${e.message}") } }
            }
        } catch (e: Exception) { KernelLog.w("AgentDocs", "tmpCleanup2: ${e.message}") }
    }

    /** Read a project memory file. */
    internal fun readProjectMemory(agentName: String, projectName: String): String {
        val file = File(DataPaths.projectMemoryFile(agentName, projectName))
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readProjectMemory"); ""
        } else ""
    }

    /** Search across all project memories by keywords. */
    internal fun searchProjectMemory(agentName: String, keywords: List<String>): String {
        val projects = DataPaths.projectMemoryFiles(agentName)
        if (projects.isEmpty() || keywords.isEmpty()) return ""
        val matched = mutableListOf<String>()
        projects.forEach { name ->
            val content = readProjectMemory(agentName, name)
            if (content.isNotBlank() && keywords.any { kw -> content.contains(kw, ignoreCase = true) }) {
                matched.add("### $name\n${content.take(500)}")
            }
        }
        return if (matched.isEmpty()) ""
        else "## 相关项目经验\n\n${matched.joinToString("\n\n")}"
    }

    // ── Single-entry operations (universal for all memory files) ──

    /**
     * Delete a SINGLE entry from any memory file by exact ## header match.
     * Safety: refuses blank IDs, IDs < 10 chars, and ambiguous (multi-match) IDs.
     * @return Number of entries deleted (0 or 1).
     */
    internal fun deleteEntry(agentName: String, filePath: String, entryId: String): Int {
        if (entryId.isBlank() || entryId.length < 10) return 0
        val file = File(filePath)
        if (!file.exists()) return 0
        return try {
            val content = file.readText()
            val entries = content.split(Regex("(?=## )")).filter { it.isNotBlank() }
            val matched = entries.filter { it.trimStart().startsWith("## $entryId") }
            if (matched.isEmpty() || matched.size > 1) return 0
            val remaining = entries.filter { it != matched[0] }
            writeAtomic(file, remaining.joinToString("\n").trim() + "\n")
            1
        } catch (e: Exception) { KernelLog.w("AgentDocs", "deleteEntry: ${e.message}"); 0 }
    }

    /**
     * Replace a SINGLE entry's content in any memory file.
     * Finds the entry by exact ## header, replaces only its content (preserves header).
     * @return Number of entries edited (0 or 1).
     */
    internal fun editEntry(agentName: String, filePath: String, entryId: String, newContent: String): Int {
        if (entryId.isBlank() || entryId.length < 10) return 0
        val file = File(filePath)
        if (!file.exists()) return 0
        return try {
            val content = file.readText()
            val entries = content.split(Regex("(?=## )")).filter { it.isNotBlank() }
            val matched = entries.filter { it.trimStart().startsWith("## $entryId") }
            if (matched.isEmpty() || matched.size > 1) return 0
            val old = matched[0]
            // Preserve the header line, replace everything after it
            val headerEnd = old.indexOf('\n')
            val header = if (headerEnd > 0) old.substring(0, headerEnd) else old.trimEnd()
            val edited = "$header\n\n$newContent"
            val remaining = entries.map { if (it == old) edited else it }
            writeAtomic(file, remaining.joinToString("\n").trim() + "\n")
            1
        } catch (e: Exception) { KernelLog.w("AgentDocs", "editEntry: ${e.message}"); 0 }
    }

    /** Count entries matching an ID in a file. Returns -1 on error. */
    internal fun countMatchingEntries(filePath: String, entryId: String): Int {
        val file = File(filePath)
        if (!file.exists()) return 0
        return try {
            val content = file.readText()
            content.split(Regex("(?=## )")).count {
                it.isNotBlank() && it.trimStart().startsWith("## $entryId")
            }
        } catch (e: Exception) { KernelLog.w("AgentDocs", "countMatchingEntries: ${e.message}"); -1 }
    }

    private fun writeAtomic(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(content)
            // 标准原子写: Files.move 覆盖 (Windows 上 renameTo 无法覆盖已存在目标,
            // 旧"先删目标"写法在 rename 失败时会丢原文件)
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            KernelLog.w("AgentDocs", "writeAtomic: ${e.message}")
        }
    }

    // ── Convenience wrappers (delegate to generic engine) ─────────

    internal fun deleteLongTermEntry(agentName: String, entryId: String): Int =
        deleteEntry(agentName, DataPaths.longTermMemoryFile(agentName), entryId)

    internal fun editLongTermEntry(agentName: String, entryId: String, newContent: String): Int =
        editEntry(agentName, DataPaths.longTermMemoryFile(agentName), entryId, newContent)

    internal fun countLongTermMatches(agentName: String, entryId: String): Int =
        countMatchingEntries(DataPaths.longTermMemoryFile(agentName), entryId)

    // ── File-level deletion ───────────────────────────────────────

    internal fun deleteMidTermFile(agentName: String, date: String): Boolean {
        val file = File(DataPaths.midTermMemoryFile(agentName, date))
        return if (file.exists()) { file.delete() } else false
    }

    internal fun deleteProjectMemory(agentName: String, projectName: String): Boolean {
        val file = File(DataPaths.projectMemoryFile(agentName, projectName))
        return if (file.exists()) { file.delete() } else false
    }

    internal fun deleteBoost(agentName: String): Boolean {
        val file = File(DataPaths.AGENTS, "$agentName/boost.md")
        return if (file.exists()) { file.delete() } else false
    }

    // ── Legacy compatibility (delegates to long-term for prompt injection) ──

    internal fun readMemoryDoc(agentName: String): String = readLongTermMemory(agentName)

    internal suspend fun readMemoryDocAsync(agentName: String): String = readLongTermMemoryAsync(agentName)

    internal fun recallMemory(agentName: String, keywords: List<String>): String =
        searchLongTermMemory(agentName, keywords)

    internal fun appendMemory(agentName: String, entry: String) = appendMidTermMemory(agentName, entry)
}
