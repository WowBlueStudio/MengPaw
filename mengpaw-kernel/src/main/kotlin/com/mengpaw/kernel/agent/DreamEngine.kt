// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.llm.LlmProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dream mode engine — two concerns:
 * 1. **Dream pass** (LLM): analyze Scroll headlines + memory + profile → terse {date}_dream.md findings
 * 2. **Dream organize** (file-only): 读 memory/ → 备份 memory/backup/ → 提炼 {date}_dream.md → 到期删除已整理分片
 * 3. **Workspace cleanup** (file-only): trim screenshots, prune temp files
 *
 * Inspired by QwenPaw's proactive mode (Apache 2.0), adapted for Android.
 */
object DreamEngine : DreamProvider {
    override val providerName: String = "kernel-default"
    // FIX A8: Use lazy getter so DataPaths.AGENTS is resolved at access time, not at class load
    private val agentsDir: File get() = File(DataPaths.AGENTS)
    private val dreamLog: File get() = File(agentsDir, "dream.log")
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

    /** LLM 提炼梦境 (DreamProvider SPI): 上下文 → 洞察 → 写入 {date}_dream.md。 */
    override suspend fun refine(
        agentName: String,
        llmProvider: LlmProvider,
        scroll: ScrollContextManager?
    ): String? {
        val ctx = buildContext(agentName, scroll) ?: return null
        val messages = listOf(
            mapOf("role" to "system", "content" to PASS_PROMPT),
            mapOf("role" to "user", "content" to ctx)
        )
        return try {
            val response = llmProvider.completeWithMessages(messages)
            val trimmed = response.take(MAX_OUTPUT_CHARS).trim()
            if (trimmed.isNotEmpty()) {
                writeDreamMd(agentName, trimmed)
                trimmed
            } else null
        } catch (e: Exception) {
            ErrorCollector.report(e, "DreamEngine.refine")
            null
        }
    }

    /** 梦境输入组装 (DreamProvider SPI): 对话摘要 + 三轨记忆 + 档案 → LLM 上下文。 */
    override suspend fun buildContext(agentName: String, scroll: ScrollContextManager?): String? {
        val parts = mutableListOf<String>()
        scroll?.let { s ->
            val headlines = s.listIndex().take(20)
            if (headlines.isNotEmpty()) parts.add("## 近期对话\n" + headlines.joinToString("\n") { "  - [${it.id}] ${it.headline}" })
        }
        // Dream analyzes mid-term memory to produce long-term insights
        // 单轨 (v0.22.0): 任务记忆已并入中期 (recordTaskMemory → appendMidTermMemory), 无需旁轨段
        val midTerm = AgentDocs.readMidTermMemory(agentName)
        if (midTerm.isNotBlank()) parts.add("## 中期记忆 (今日)\n${midTerm.take(800)}")
        val longTerm = AgentDocs.readLongTermMemory(agentName)
        if (longTerm.isNotBlank()) parts.add("## 长期记忆 (已有)\n${longTerm.take(400)}")
        // FIX: Use lowercase "profile.md" consistent with AgentDocManager/AgentDocs
        val profile = File(agentsDir, "$agentName/profile.md")
        if (profile.exists()) parts.add("## 档案\n${try { profile.readText().take(600) } catch (e: Exception) { ErrorCollector.report(e, "DreamEngine.buildContext"); "" }}")
        if (parts.isEmpty()) return null
        val combined = parts.joinToString("\n\n")
        return if (combined.length > MAX_CONTEXT_CHARS) combined.take(MAX_CONTEXT_CHARS) else combined
    }

    /** 梦境产物文件名: {date}_dream.md (工作区根, 随孪生工作区同步传播) */
    private fun dreamFileName(): String = "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}_dream.md"

    /** 写入 {agent}/{date}_dream.md — 新条目前置, 同日多次梦境追加到同一文件 */
    private fun writeDreamMd(agentName: String, content: String) {
        try {
            val dir = File(agentsDir, agentName); if (!dir.exists()) dir.mkdirs()
            val entry = "\n---\n## ${DATE_FMT.format(Date())}\n\n$content\n"
            val file = File(dir, dreamFileName())
            val existing = if (file.exists()) try { file.readText() } catch (e: Exception) { ErrorCollector.report(e, "DreamEngine.writeDreamMd"); "" } else "# $agentName · 梦境记录\n"
            file.writeText(entry + existing)
        } catch (e: Exception) {
            ErrorCollector.report(e, "DreamEngine.writeDreamMd")
        }
    }

    /** 梦境产物是否已存在 (DreamWorker 节流检查用) */
    fun hasTodayDream(agentName: String): Boolean =
        File(agentsDir, "$agentName/${dreamFileName()}").exists()

    // ── Workspace Cleanup (existing) ─────────────────────────────────

    data class CleanupResult(val filesDeleted: Int, val bytesFreed: Long, val dirsCleaned: List<String>)

    fun cleanupWorkspace(): CleanupResult {
        var deleted = 0; var freed = 0L; val cleaned = mutableListOf<String>()
        val cutoff3d = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        val cutoff30d = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000

        val ss = File(DataPaths.SCREENSHOTS)
        if (ss.exists()) {
            var n = 0
            try { ss.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff3d && f.length() > 50 * 1024 && !f.name.contains("thumb", true)) {
                    freed += f.length(); f.delete(); deleted++; n++
                }
            } } catch (_: Exception) { /* best-effort cleanup */ }
            if (n > 0) cleaned.add("截图原图(${n}张)")
        }

        listOf(File(agentsDir, "inbox"), File(agentsDir, "team/inbox")).forEach { dir ->
            if (dir.exists()) {
                val n = try { dir.listFiles()?.count { it.lastModified() < cutoff30d && run { freed += it.length(); it.delete(); true } } ?: 0 } catch (_: Exception) { 0 }
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

    /** 备份保留期限: 30 天 */
    private const val BACKUP_RETENTION_DAYS = 30L

    /**
     * 梦境整理 (单轨 v0.22.0) — 四步管道:
     * 1. 读取 memory/ 全部中期分片 (memory_{date}.md)
     * 2. 备份到 memory/backup/
     * 3. 提炼 → 产出 {agent}/{date}_dream.md
     * 4. 到期删除: 已整理分片从 memory/ 移除; backup/ 中 30 天前的备份删除
     */
    /** 文件整理 (DreamProvider SPI): 备份 → 摘录 → 到期删除。 */
    override fun organize(agentName: String): DreamResult {
        val midDir = File(DataPaths.midTermMemoryDir(agentName))
        if (!midDir.exists()) return DreamResult(0, 0, "无中期记忆目录")
        val dateFiles = midDir.listFiles()
            ?.filter { it.name.startsWith("memory_") && it.name.endsWith(".md") }
            ?.sorted()
            ?: emptyList()
        if (dateFiles.isEmpty()) return DreamResult(0, 0, "无待整理分片")

        val backupDir = File(midDir, "backup")
        try { backupDir.mkdirs() } catch (e: Exception) { ErrorCollector.report(e, "DreamEngine.dream.mkdir") }
        val reviewed = dateFiles.size
        var archived = 0

        // 1+2+3: 读分片 → 备份 → 汇总提炼内容
        val digest = buildString {
            appendLine("# 梦境整理 · ${DATE_FMT.format(Date())}")
            appendLine()
            appendLine("> 来源: ${dateFiles.size} 个中期分片 | 已备份到 memory/backup/")
            appendLine()
            dateFiles.forEach { f ->
                val content = try { f.readText() } catch (e: Exception) { ErrorCollector.report(e, "DreamEngine.dream.read"); "" }
                if (content.isBlank()) { f.delete(); return@forEach }
                // 备份
                try { f.copyTo(File(backupDir, f.name), overwrite = true) } catch (e: Exception) { ErrorCollector.report(e, "DreamEngine.dream.backup") }
                archived++
                // 提炼: 分片摘录进梦境文档
                appendLine("## ${f.name}")
                appendLine()
                appendLine(content.trim().take(2000))
                appendLine()
                // 4a: 已整理分片从 memory/ 删除
                f.delete()
            }
        }

        if (digest.isNotBlank()) writeDreamMd(agentName, digest)

        // 4b: backup/ 中 30 天前的备份到期删除
        val cutoff = System.currentTimeMillis() - BACKUP_RETENTION_DAYS * 24 * 3600 * 1000
        try {
            backupDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        } catch (_: Exception) { /* best-effort */ }

        val result = DreamResult(reviewed, archived, "备份 ${archived} 个分片到 memory/backup/")
        try {
            if (!dreamLog.exists()) dreamLog.parentFile?.mkdirs()
            dreamLog.appendText("${DATE_FMT.format(Date())} | agent=$agentName | reviewed=$reviewed archived=$archived (→ memory/backup/)\n")
        } catch (_: Exception) { /* best-effort log */ }
        return result
    }

    /** 梦境统计 (DreamProvider SPI)。 */
    override fun stats(): String {
        if (!dreamLog.exists()) return "总计: 0 次"
        val lines = try { dreamLog.readLines() } catch (e: Exception) { KernelLog.w("DreamEngine", "dreamStats: ${e.message}"); return "总计: 0 次" }
        return "梦境: ${lines.size} 次"
    }

    /** 梦境历史 (DreamProvider SPI)。 */
    override fun history(limit: Int): String = dreamHistory(limit)

    // ── Internal ─────────────────────────────────────────────────────

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var s = 0L
        try { dir.listFiles()?.forEach { s += if (it.isDirectory) dirSize(it) else it.length() } } catch (_: Exception) { /* best-effort */ }
        return s
    }

    // FIX: Correct unit conversions — was dividing by 1024³ (GB) for MB label
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
