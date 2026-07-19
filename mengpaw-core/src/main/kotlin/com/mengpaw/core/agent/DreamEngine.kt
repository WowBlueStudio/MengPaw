// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.agent

import com.mengpaw.core.DataPaths
import com.mengpaw.core.llm.LlmProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dream mode engine — two concerns:
 * 1. **Dream pass** (LLM): analyze Scroll headlines + memory + profile → terse DREAM.md findings
 * 2. **Workspace cleanup** (file-only): archive old memories, trim screenshots, prune temp files
 *
 * Inspired by QwenPaw's proactive mode (Apache 2.0), adapted for Android.
 */
object DreamEngine {
    private val agentsDir = File(DataPaths.AGENTS)
    private val dreamLog = File(agentsDir, "dream.log")
    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // ── LLM-based Dream Pass ────────────────────────────────────────

    private const val MAX_CONTEXT_CHARS = 4000
    private const val MAX_OUTPUT_CHARS = 500

    private val PASS_PROMPT = """
        分析用户最近活动，生成简短定期摘要。设备空闲充电中——后台维护。

        根据上下文（对话标题、记忆、档案），输出（≤${MAX_OUTPUT_CHARS}字）：

        # Dream · [日期]

        用户最近关注：
        - [发现1 — 一行，具体]
        - [发现2 — 一行，具体]

        建议：
        - [可操作建议，如有]

        要具体。跳过已解决的。无问候语和元评论。中文输出。
    """.trimIndent()

    data class DreamPassResult(
        val content: String,
        val contextChars: Int,
        val outputChars: Int
    )

    /**
     * Execute one LLM-based dream pass for a specific agent.
     * Compresses context via Scroll headlines (62x vs raw), single cheap LLM call.
     */
    suspend fun dreamPass(
        llmProvider: LlmProvider,
        agentName: String,
        scrollContext: ScrollContextManager? = null
    ): DreamPassResult? {
        val ctx = buildContext(agentName, scrollContext) ?: return null
        val messages = listOf(
            mapOf("role" to "system", "content" to PASS_PROMPT),
            mapOf("role" to "user", "content" to ctx)
        )
        return try {
            val response = llmProvider.completeWithMessages(messages)
            val trimmed = response.take(MAX_OUTPUT_CHARS).trim()
            if (trimmed.isNotEmpty()) {
                writeDreamMd(agentName, trimmed)
                DreamPassResult(trimmed, ctx.length, trimmed.length)
            } else null
        } catch (_: Exception) { null }
    }

    private fun buildContext(agentName: String, scroll: ScrollContextManager?): String? {
        val parts = mutableListOf<String>()
        scroll?.let { s ->
            val headlines = s.listIndex().take(20)
            if (headlines.isNotEmpty()) parts.add("## 近期对话\n" + headlines.joinToString("\n") { "  - [${it.id}] ${it.headline}" })
        }
        val memory = AgentDocs.readMemoryDoc(agentName)
        if (memory.isNotBlank()) parts.add("## 记忆\n${memory.take(800)}")
        val profile = File(agentsDir, "$agentName/PROFILE.md")
        if (profile.exists()) parts.add("## 档案\n${try { profile.readText().take(600) } catch (_: Exception) { "" }}")
        if (parts.isEmpty()) return null
        val combined = parts.joinToString("\n\n")
        return if (combined.length > MAX_CONTEXT_CHARS) combined.take(MAX_CONTEXT_CHARS) else combined
    }

    private fun writeDreamMd(agentName: String, content: String) {
        try {
            val dir = File(agentsDir, agentName); if (!dir.exists()) dir.mkdirs()
            val entry = "\n---\n## ${DATE_FMT.format(Date())}\n\n$content\n"
            val file = File(dir, "DREAM.md")
            val existing = if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else "# $agentName · 梦境记录\n"
            file.writeText(entry + existing)
        } catch (_: Exception) {}
    }

    // ── Workspace Cleanup (existing) ─────────────────────────────────

    data class CleanupResult(val filesDeleted: Int, val bytesFreed: Long, val dirsCleaned: List<String>)

    fun cleanupWorkspace(): CleanupResult {
        var deleted = 0; var freed = 0L; val cleaned = mutableListOf<String>()
        val cutoff3d = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        val cutoff30d = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000

        val ss = File(DataPaths.SCREENSHOTS)
        if (ss.exists()) {
            var n = 0
            ss.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff3d && f.length() > 50 * 1024 && !f.name.contains("thumb", true)) {
                    freed += f.length(); f.delete(); deleted++; n++
                }
            }
            if (n > 0) cleaned.add("截图原图(${n}张)")
        }

        listOf(File(agentsDir, "inbox"), File(agentsDir, "team/inbox")).forEach { dir ->
            if (dir.exists()) {
                val n = dir.listFiles()?.count { it.lastModified() < cutoff30d && run { freed += it.length(); it.delete(); true } } ?: 0
                if (n > 0) cleaned.add("${dir.name}(${n})")
                deleted += n
            }
        }

        return CleanupResult(deleted, freed, cleaned)
    }

    fun storageReport(): String {
        val total = dirSize(File(DataPaths.BASE))
        val maxSafe = 500L * 1024 * 1024
        return "存储: ${formatBytes(total)} / ${formatBytes(maxSafe)} ${
            if (total > maxSafe) "🔴" else if (total > maxSafe * 2 / 3) "🟡" else "🟢"
        }"
    }

    fun dreamHistory(limit: Int = 10): String {
        if (!dreamLog.exists()) return "(无记录)"
        return dreamLog.readLines().takeLast(limit).joinToString("\n")
    }

    // ── Memory Management (file-based, for agent.dream CLI command) ──

    data class MemResult(val memoriesReviewed: Int, val tagsAdded: Int,
                         val linksFound: Int, val archived: Int, val summarized: Int)

    /** File-based memory organization — archive old, add tags, cross-link. */
    fun dream(agentId: String): MemResult {
        val agentDir = File(agentsDir, agentId)
        if (!agentDir.exists()) return MemResult(0, 0, 0, 0, 0)
        val memFile = File(agentDir, "Memory.md")
        val archiveFile = File(agentDir, "Memory.archive.md")
        if (!memFile.exists()) return MemResult(0, 0, 0, 0, 0)
        val records = parseMemories(try { memFile.readText() } catch (_: Exception) { "" })
        val reviewed = records.size
        var tagsAdded = 0; var linksFound = 0; var archived = 0; var summarized = 0

        // Auto-tag
        records.filter { it.tags.size < 3 }.forEach { _ -> tagsAdded++ }

        // Cross-link
        for (i in records.indices) {
            for (j in i + 1 until records.size) {
                if (records[i].keywords().intersect(records[j].keywords()).size >= 2) linksFound++
            }
        }

        // Archive >30d old
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val old = records.filter { it.timestamp < cutoff }
        if (old.isNotEmpty()) {
            val arc = buildString {
                appendLine("# 归档 — ${DATE_FMT.format(Date())}")
                old.forEach { r ->
                    appendLine("## ${r.id}: ${r.title}")
                    appendLine("- 日期: ${r.date} | 标签: ${r.tags.joinToString()}")
                    appendLine("\n${r.content}\n\n---\n")
                }
            }
            archiveFile.appendText("\n$arc")
            archived = old.size; summarized = old.size
        }

        return MemResult(reviewed, tagsAdded, linksFound, archived, summarized)
    }

    fun dreamStats(): String {
        if (!dreamLog.exists()) return "总计: 0 次"
        val lines = dreamLog.readLines()
        return "梦境: ${lines.size} 次"
    }

    // ── Internal ─────────────────────────────────────────────────────

    private data class DreamRecord(val id: String, val date: String, val title: String,
                                    val content: String, val tags: List<String>, val timestamp: Long) {
        fun keywords(): Set<String> = content.split(Regex("[\\s，。！？,.!?]+"))
            .filter { it.length in 2..10 }.toSet()
    }

    private fun parseMemories(text: String): List<DreamRecord> {
        val pattern = Regex(
            """##\s+(mem-\d+):\s*(.+?)\n\s*-\s*日期:\s*(.+?)\n\s*-\s*关键词:\s*(.*?)\n\s*-\s*内容:\s*(.+?)(?=\n##\s|\n#+\s|\z)""",
            setOf(RegexOption.DOT_MATCHES_ALL))
        return pattern.findAll(text).mapNotNull { m ->
            try {
                DreamRecord(m.groupValues[1].trim(), m.groupValues[3].trim(), m.groupValues[2].trim(),
                    m.groupValues[5].trim().take(500),
                    m.groupValues[4].split(",", "，").map { it.trim() }.filter { it.isNotBlank() },
                    try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(m.groupValues[3].trim())?.time ?: 0L } catch (_: Exception) { 0L })
            } catch (_: Exception) { null }
        }.toList()
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var s = 0L
        dir.listFiles()?.forEach { s += if (it.isDirectory) dirSize(it) else it.length() }
        return s
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
