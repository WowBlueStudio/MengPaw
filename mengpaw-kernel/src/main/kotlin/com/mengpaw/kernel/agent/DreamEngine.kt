// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.memory.MemoryIndexWriter
import com.mengpaw.kernel.memory.MemoryLedger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dream mode engine — P3: 归纳从"读取并删除中期分片"降级为**账本视图**。
 *
 * 1. **Dream pass** (LLM): 查账本未归纳条目 + 对话摘要 + 长期记忆/档案 → `{date}_dream.md`
 * 2. **Dream organize** (file-only): 查账本 (未归纳/日期/项目) → 生成带 `sources[]` 的视图
 *    → 追加 digest 条目标记已归纳; **不删除分片**; 重跑生成 `.v2/.v3…` 不覆盖旧产物
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
    private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val DREAM_NAME = Regex("^\\d{4}-\\d{2}-\\d{2}_dream(?:\\.v(\\d+))?\\.md$")

    // ── LLM-based Dream Pass ────────────────────────────────────────

    private const val MAX_CONTEXT_CHARS = 4000
    private const val MAX_OUTPUT_CHARS = 500
    private const val MAX_LEDGER_ENTRIES = 40
    private const val MAX_LEDGER_CONTEXT_CHARS = 1200

    private val PASS_PROMPT = """
        分析用户最近活动，生成简短定期摘要。设备空闲充电中——后台维护。

        根据上下文（对话标题、记忆条目、档案），输出（≤${MAX_OUTPUT_CHARS}字）：

        # Dream · [日期]

        用户最近关注：
        - [发现1 — 一行，具体]
        - [发现2 — 一行，具体]

        建议：
        - [可操作建议，如有]

        要具体。跳过已解决的。无问候语和元评论。中文输出。
    """.trimIndent()

    /** LLM 提炼梦境 (DreamProvider SPI): 上下文 → 洞察 → 写入 {date}_dream[.vN].md。 */
    override suspend fun refine(
        agentName: String,
        llmProvider: LlmProvider,
        scroll: ScrollContextManager?
    ): String? {
        val built = buildContextWithSources(agentName, scroll)
        val ctx = built.first ?: return null
        val messages = listOf(
            mapOf("role" to "system", "content" to PASS_PROMPT),
            mapOf("role" to "user", "content" to ctx)
        )
        return try {
            val response = llmProvider.completeWithMessages(messages)
            val trimmed = response.take(MAX_OUTPUT_CHARS).trim()
            if (trimmed.isNotEmpty()) {
                val file = writeDreamMd(agentName, trimmed, built.second)
                if (file != null && built.second.isNotEmpty()) {
                    MemoryLedger.appendDigest(agentName, file.name, built.second, trimmed)
                    MemoryIndexWriter.rebuild(agentName)
                }
                trimmed
            } else null
        } catch (e: Exception) {
            ErrorCollector.report(e, "DreamEngine.refine")
            null
        }
    }

    /** 梦境输入组装 (DreamProvider SPI): 对话摘要 + 账本未归纳条目 + 档案 → LLM 上下文。 */
    override suspend fun buildContext(agentName: String, scroll: ScrollContextManager?): String? =
        buildContextWithSources(agentName, scroll).first

    /** 返回 (上下文, 本次引用的 entryId 列表) — `sources[]` 强制字段的数据来源。 */
    private fun buildContextWithSources(
        agentName: String,
        scroll: ScrollContextManager?
    ): Pair<String?, List<String>> {
        val parts = mutableListOf<String>()
        val sources = mutableListOf<String>()
        scroll?.let { s ->
            val headlines = s.listIndex().take(20)
            if (headlines.isNotEmpty()) parts.add("## 近期对话\n" + headlines.joinToString("\n") { "  - [${it.id}] ${it.headline}" })
        }
        // P3: 输入改为"查账本未归纳条目", 不再读全部中期分片; 旧分片仅作 unsourced 兜底
        val pending = MemoryLedger.undigested(agentName).take(MAX_LEDGER_ENTRIES)
        val rendered = mutableListOf<String>()
        var usedChars = 0
        for (entry in pending) {
            val block = MemoryLedger.resolveBlock(agentName, entry) ?: continue
            val section = "### ${entry.entryId} (${entry.file})\n$block"
            if (rendered.isNotEmpty() && usedChars + section.length > MAX_LEDGER_CONTEXT_CHARS) break
            rendered.add(section)
            sources.add(entry.entryId)
            usedChars += section.length
        }
        if (rendered.isNotEmpty()) {
            parts.add("## 中期记忆（账本未归纳 ${rendered.size} 条）\n" + rendered.joinToString("\n\n").take(1200))
        } else {
            val legacy = AgentDocs.readMidTermMemory(agentName)
            if (legacy.isNotBlank()) parts.add("## 中期记忆 (今日分片, 未入账本 · unsourced)\n${legacy.take(800)}")
        }
        val longTerm = AgentDocs.readLongTermMemory(agentName)
        if (longTerm.isNotBlank()) parts.add("## 长期记忆 (已有)\n${longTerm.take(400)}")
        // FIX: Use lowercase "profile.md" consistent with AgentDocManager/AgentDocs
        val profile = File(agentsDir, "$agentName/profile.md")
        if (profile.exists()) parts.add("## 档案\n${try { profile.readText().take(600) } catch (e: Exception) { ErrorCollector.report(e, "DreamEngine.buildContext"); "" }}")
        if (parts.isEmpty()) return null to emptyList()
        val combined = parts.joinToString("\n\n")
        val context = if (combined.length > MAX_CONTEXT_CHARS) combined.take(MAX_CONTEXT_CHARS) else combined
        return context to sources.distinct()
    }

    private fun today(): String = DAY_FMT.format(Date())

    /**
     * 写入新梦境产物 — 重跑不覆盖: 首版 `{date}_dream.md`, 之后 `.v2.md`, `.v3.md`…
     * @return 写入的文件; IO 失败 → null
     */
    private fun writeDreamMd(agentName: String, content: String, sources: List<String>): File? {
        return try {
            val dir = File(agentsDir, agentName).also { if (!it.exists()) it.mkdirs() }
            val file = nextDreamFile(dir)
            val body = buildString {
                appendLine("# $agentName · 梦境记录")
                appendLine()
                appendLine("## ${DATE_FMT.format(Date())}")
                appendLine()
                appendLine(content.trim())
                appendLine()
                appendLine(sourcesLine(sources))
                if (sources.isEmpty()) appendLine("- unsourced: 本条为 LLM 归纳, 无法逐条追溯 entryId")
            }
            file.writeText(body)
            file
        } catch (e: Exception) {
            ErrorCollector.report(e, "DreamEngine.writeDreamMd")
            null
        }
    }

    /** 强制来源声明: 无来源 → `sources: [unsourced]` (§6.3)。 */
    private fun sourcesLine(sources: List<String>): String =
        "sources: [" + (if (sources.isEmpty()) "unsourced" else sources.distinct().joinToString(", ")) + "]"

    /** 当天下一版产物: `{date}_dream.md` → `.v2.md` → `.v3.md`… */
    private fun nextDreamFile(dir: File): File {
        val base = "${today()}_dream"
        val first = File(dir, "$base.md")
        if (!first.exists()) return first
        var version = 2
        while (File(dir, "$base.v$version.md").exists()) version++
        return File(dir, "$base.v$version.md")
    }

    /** 梦境产物是否已存在 (DreamWorker 节流检查用; 任一版本都算)。 */
    fun hasTodayDream(agentName: String): Boolean {
        val dir = File(agentsDir, agentName)
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return false
        return files.any { it.isFile && DREAM_NAME.matches(it.name) && it.name.startsWith(today()) }
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

    /**
     * 梦境整理 (P3) — 查账本未归纳条目 (重跑时回退到全部条目) → 生成带 sources 的视图
     * → 追加 digest 条目标记已归纳。**不删除分片**; 旧产物保留, 新产物版本化。
     */
    /** 文件整理 (DreamProvider SPI): 查账本 → 视图 (带 sources) → 标记已归纳。 */
    override fun organize(agentName: String): DreamResult {
        val all = MemoryLedger.entries(agentName)
        val pending = MemoryLedger.undigested(all)
        // 重跑 (无新条目) 用全部非 digest 条目 → 产出 .v2, 旧产物保留
        val candidates = if (pending.isEmpty()) all.filter { it.kind != MemoryLedger.KIND_DIGEST } else pending
        val ledgerFiles = all.map { it.file }.toSet()
        val legacyFiles = legacyFragments(agentName, ledgerFiles)
        if (candidates.isEmpty() && legacyFiles.isEmpty()) return DreamResult(0, 0, "无待归纳条目")
        val sources = candidates.map { it.entryId }.distinct()

        val digest = buildString {
            appendLine("# 梦境整理 · ${DATE_FMT.format(Date())}")
            appendLine()
            appendLine("> 来源: 账本 ${candidates.size} 条 (${candidates.map { it.file }.distinct().size} 个文件), 旧分片 ${legacyFiles.size} 个")
            appendLine("> 账本只增: 分片不删除; 已归纳条目标记在 digest 条目")
            appendLine()
            candidates.groupBy { it.file }.toSortedMap().forEach { (file, entries) ->
                appendLine("## $file")
                appendLine()
                entries.sortedWith(compareBy({ it.wall }, { it.entryId })).forEach { entry ->
                    val block = MemoryLedger.resolveBlock(agentName, entry)
                    appendLine("### ${entry.entryId}")
                    if (block != null) {
                        appendLine(block)
                    } else {
                        appendLine("(正文不可解析: blockHash ${entry.blockHash})")
                    }
                    appendLine()
                    appendLine(sourcesLine(listOf(entry.entryId)))
                    appendLine()
                }
            }
            legacyFiles.forEach { (relPath, content) ->
                appendLine("## $relPath (未入账本)")
                appendLine()
                appendLine(content.trim().take(2000))
                appendLine()
                appendLine(sourcesLine(emptyList()))
                appendLine()
            }
        }

        val file = writeDreamMd(agentName, digest, sources) ?: return DreamResult(candidates.size, 0, "梦境视图写入失败")
        if (sources.isNotEmpty()) {
            MemoryLedger.appendDigest(agentName, file.name, sources, digest)
            MemoryIndexWriter.rebuild(agentName)
        }
        try {
            if (!dreamLog.exists()) dreamLog.parentFile?.mkdirs()
            dreamLog.appendText("${DATE_FMT.format(Date())} | agent=$agentName | entries=${candidates.size} legacy=${legacyFiles.size} (→ ${file.name}, 分片保留)\n")
        } catch (_: Exception) { /* best-effort log */ }
        return DreamResult(candidates.size + legacyFiles.size, 0, "已归纳 ${candidates.size} 条 → ${file.name}; 分片保留")
    }

    /** 未进账本的旧中期分片 (升级前数据): 相对路径 → 正文。 */
    private fun legacyFragments(agentName: String, ledgerFiles: Set<String>): List<Pair<String, String>> {
        val dir = File(DataPaths.midTermMemoryDir(agentName))
        if (!dir.isDirectory) return emptyList()
        val files = try { dir.listFiles()?.filter { it.isFile && it.name.startsWith("memory_") && it.name.endsWith(".md") } }
            catch (_: Exception) { null } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            val rel = "memory/${f.name}"
            if (rel in ledgerFiles) return@mapNotNull null
            val text = try { f.readText() } catch (e: Exception) {
                ErrorCollector.report(e, "DreamEngine.legacyFragments")
                return@mapNotNull null
            }
            if (text.isBlank()) null else rel to text
        }
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
