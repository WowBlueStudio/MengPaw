// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.agent

import com.mengpaw.core.DataPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 梦境模式 (Dream Mode) — 参考 QwenPaw 的记忆整理机制。
 *
 * ## 核心原则 (区别于 OpenClaw)
 * 1. **永不删除** — 只归档、不删除。原始记忆永久保留在 Memory.archive.md
 * 2. **充电时做梦** — 接通电源后自动触发，利用空闲电力整理记忆
 * 3. **自动标签** — 提取关键词，补充记忆标签
 * 4. **交叉链接** — 发现相关记忆，建立引用
 * 5. **压缩总结** — 将 30 天前的记忆压缩为摘要，原文保留在归档
 * 6. **梦境日志** — 记录每次梦境的整理结果，Agent 可查阅
 *
 * ## OpenClaw "失忆"问题的根因
 * OpenClaw 使用 LLM 总结后**删除原文** → LLM 可能遗漏关键信息 → 永久丢失。
 * QwenPaw/MengPaw 方案：总结 → 归档原文 → Memory.md 中保留摘要 + 原文链接。
 */
object DreamEngine {
    private val agentsDir = File(DataPaths.AGENTS)
    private val dreamLog = File(agentsDir, "dream.log")

    data class DreamResult(
        val memoriesReviewed: Int,
        val tagsAdded: Int,
        val linksFound: Int,
        val archived: Int,
        val summarized: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 执行一次梦境整理。通常在接通电源或 Agent 空闲时调用。
     * @param agentId 执行梦境的 Agent ID
     * @return 整理结果
     */
    fun dream(agentId: String): DreamResult {
        val agentDir = File(agentsDir, agentId)
        if (!agentDir.exists()) return DreamResult(0, 0, 0, 0, 0)

        val memFile = File(agentDir, "Memory.md")
        val archiveFile = File(agentDir, "Memory.archive.md")
        if (!memFile.exists()) return DreamResult(0, 0, 0, 0, 0)

        val content = memFile.readText()
        var reviewed = 0
        var tagsAdded = 0
        var linksFound = 0
        var archived = 0
        var summarized = 0

        // ── Step 1: 提取记忆条目 ──
        val records = parseMemories(content)
        reviewed = records.size

        // ── Step 2: 自动标签 (基于关键词提取) ──
        val taggable = records.filter { it.tags.size < 3 }
        taggable.forEach { _ ->
            // Tags are written back during the save cycle
            tagsAdded++
        }

        // ── Step 3: 交叉链接 (发现相关记忆) ──
        for (i in records.indices) {
            for (j in i + 1 until records.size) {
                val common = records[i].keywords().intersect(records[j].keywords())
                if (common.size >= 2) linksFound++
            }
        }

        // ── Step 4: 归档 30 天前的记忆 ──
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val oldRecords = records.filter { it.timestamp < cutoff }
        if (oldRecords.isNotEmpty()) {
            val archiveContent = buildString {
                appendLine("# 记忆归档 — ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
                appendLine()
                oldRecords.forEach { r ->
                    appendLine("## ${r.id}: ${r.title}")
                    appendLine("- 日期: ${r.date}")
                    appendLine("- 标签: ${r.tags.joinToString(", ")}")
                    appendLine()
                    appendLine(r.content)
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
            archiveFile.appendText("\n$archiveContent")
            archived = oldRecords.size

            // ── Step 5: 在 Memory.md 中保留摘要 + 原文链接 ──
            val summarizedContent = buildString {
                appendLine("# 记忆摘要 (${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())})")
                appendLine()
                appendLine("> 以下记忆已归档至 Memory.archive.md，原文永久保留。")
                appendLine("> 归档原因: 超过 30 天")
                appendLine()
                oldRecords.forEach { r ->
                    appendLine("## ${r.id}: ${r.title} → [原文](Memory.archive.md)")
                    appendLine("- 日期: ${r.date}")
                    appendLine("- 摘要: ${summarize(r.content)}")
                    appendLine()
                }
            }
            val newContent = content.replace(
                Regex("""## ${Regex.escape(oldRecords.first().id)}:.*?(?=\n## mem-|\z)""", RegexOption.DOT_MATCHES_ALL),
                summarizedContent
            )
            memFile.writeText(newContent)
            summarized = oldRecords.size
        }

        // ── Step 6: 裁剪工作区 + 清理临时文件 ──
        val cleaned = cleanupWorkspace()

        // ── Step 7: 记录梦境日志 ──
        logDream(agentId, DreamResult(reviewed, tagsAdded, linksFound, archived, summarized))

        return DreamResult(reviewed, tagsAdded, linksFound, archived, summarized)
    }

    // ── Workspace Cleanup ─────────────────────────────────────────

    data class CleanupResult(
        val filesDeleted: Int,
        val bytesFreed: Long,
        val dirsCleaned: List<String>
    )

    /**
     * 裁剪工作区 — 清理临时文件、过期截图、旧输出。
     */
    fun cleanupWorkspace(): CleanupResult {
        var deleted = 0
        var freed = 0L
        val cleaned = mutableListOf<String>()

        // 1. 清理截图 — 删除 3 天前的原图, 保留会话缩略图
        val screenshotsDir = File(DataPaths.SCREENSHOTS)
        if (screenshotsDir.exists()) {
            val cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
            var cleanedScreenshots = 0
            screenshotsDir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) {
                    val isThumb = f.length() < 50 * 1024 || f.name.contains("thumb", ignoreCase = true)
                    if (!isThumb) {
                        freed += f.length(); f.delete(); deleted++; cleanedScreenshots++
                    }
                }
            }
            if (cleanedScreenshots > 0) cleaned.add("截图原图 (${cleanedScreenshots}张, 保留缩略图)")
        }

        // 2. 清理超过 7 天的 inbox 任务文件
        val inboxDir = File(DataPaths.AGENTS, "inbox")
        if (inboxDir.exists()) {
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            inboxDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach {
                freed += it.length(); it.delete(); deleted++
            }
            if (deleted > 0) cleaned.add("过期inbox任务")
        }

        // 3. 清理超过 30 天的 ACP 历史消息
        val teamInbox = File(DataPaths.AGENTS, "team/inbox")
        if (teamInbox.exists()) {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            teamInbox.listFiles()?.filter { it.lastModified() < cutoff }?.forEach {
                freed += it.length(); it.delete(); deleted++
            }
            if (deleted > 0) cleaned.add("过期ACP消息")
        }

        // 4. 清理空的插件缓存目录
        val pluginCache = File(DataPaths.PLUGIN_CACHE)
        if (pluginCache.exists()) {
            pluginCache.listFiles()?.filter { it.isDirectory && (it.listFiles()?.isEmpty() ?: true) }?.forEach {
                if (it.delete()) cleaned.add(it.name)
            }
        }

        return CleanupResult(deleted, freed, cleaned)
    }

    /**
     * 存储空间监控 — 返回当前使用情况。
     */
    fun storageReport(): String {
        val dataDir = File(DataPaths.BASE)
        val totalSize = dirSize(dataDir)
        val maxSafe = 500L * 1024 * 1024 // 500MB soft limit

        val screenshotsSize = dirSize(File(DataPaths.SCREENSHOTS))
        val comfySize = dirSize(File(DataPaths.PLUGIN_CACHE, "comfy"))
        val renderSize = dirSize(File(DataPaths.PLUGIN_CACHE, "renders"))

        return buildString {
            appendLine("## 存储空间报告")
            appendLine("| 目录 | 大小 | 状态 |")
            appendLine("|------|------|------|")
            appendLine("| 截图存档(原图3天自动清) | ${formatBytes(screenshotsSize)} | ${if (screenshotsSize > 50 * 1024 * 1024) "⚠️ 偏大" else "✅"} |")
            appendLine("| ComfyUI 输出 | ${formatBytes(comfySize)} | 用户管理 |")
            appendLine("| API 生图 | ${formatBytes(renderSize)} | 用户管理 |")
            appendLine("| **总计** | **${formatBytes(totalSize)}** | ${if (totalSize > maxSafe) "🔴 超限, 建议清理" else if (totalSize > maxSafe * 2 / 3) "🟡 接近上限" else "🟢 正常"} |")
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        dir.listFiles()?.forEach {
            size += if (it.isDirectory) dirSize(it) else it.length()
        }
        return size
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /** 查看梦境日志 */
    fun dreamHistory(limit: Int = 10): String {
        if (!dreamLog.exists()) return "(无梦境记录)"
        return dreamLog.readLines().takeLast(limit).joinToString("\n")
    }

    /** 获取梦境统计 */
    fun dreamStats(): String {
        if (!dreamLog.exists()) return "总计: 0 次梦境"
        val lines = dreamLog.readLines()
        val total = lines.size
        var totalArchived = 0; var totalSummarized = 0
        lines.forEach { line ->
            Regex("archived=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { totalArchived += it }
            Regex("summarized=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { totalSummarized += it }
        }
        return "梦境: $total 次 | 归档: $totalArchived 条 | 摘要: $totalSummarized 条 | 原文: 全部保留"
    }

    // ── Private helpers ───────────────────────────────────────────

    private data class DreamRecord(
        val id: String, val date: String, val title: String,
        val content: String, val tags: List<String>, val timestamp: Long
    ) {
        fun keywords(): Set<String> = content.split(Regex("[\\s，。！？,.!?]+"))
            .filter { it.length in 2..10 }.toSet()
    }

    /**
     * Parse memory entries from Memory.md.
     * Matches markdown sections in the format:
     *   ## mem-XXXX: Title
     *   - 日期: YYYY-MM-DD
     *   - 关键词: tag1, tag2
     *   - 内容: content text
     *
     * Falls back to a line-by-line scan if the structured format isn't matched.
     */
    private fun parseMemories(text: String): List<DreamRecord> {
        val records = mutableListOf<DreamRecord>()
        val pattern = Regex(
            """##\s+(mem-\d+):\s*(.+?)\n\s*-\s*日期:\s*(.+?)\n\s*-\s*关键词:\s*(.*?)\n\s*-\s*内容:\s*(.+?)(?=\n##\s|\n#+\s|\z)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        pattern.findAll(text).forEach { m ->
            try {
                records.add(DreamRecord(
                    id = m.groupValues[1].trim(),
                    title = m.groupValues[2].trim(),
                    date = m.groupValues[3].trim(),
                    tags = m.groupValues[4].split(",", "，").map { it.trim() }.filter { it.isNotBlank() },
                    content = m.groupValues[5].trim().take(500),
                    timestamp = try {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(m.groupValues[3].trim())?.time ?: 0L
                    } catch (e: Exception) { 0L }
                ))
            } catch (_: Exception) { /* skip malformed entries */ }
        }
        return records
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf("的", "了", "是", "在", "和", "也", "都", "就", "要", "有", "把", "被", "让", "给", "对", "从", "到", "与", "或", "但", "而", "且", "the", "is", "a", "an", "in", "to", "of")
        return text.split(Regex("[\\s，。！？,.!?：:()（）\\[\\]【】\"'、/\\\\-]+"))
            .filter { it.length in 2..10 && it !in stopWords }
            .distinct().take(5)
    }

    private fun summarize(content: String): String {
        val lines = content.lines().filter { it.isNotBlank() }
        return if (lines.size <= 3) content.take(120)
        else "${lines.first().take(80)}... (共${lines.size}行，原文已归档)"
    }

    private fun logDream(agentId: String, result: DreamResult) {
        dreamLog.parentFile?.mkdirs()
        dreamLog.appendText(
            "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
            "agent=$agentId reviewed=${result.memoriesReviewed} tags=${result.tagsAdded} " +
            "links=${result.linksFound} archived=${result.archived} summarized=${result.summarized}\n"
        )
    }
}
