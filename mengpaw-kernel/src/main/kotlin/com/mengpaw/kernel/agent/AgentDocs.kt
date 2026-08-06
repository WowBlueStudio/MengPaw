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
 * Agent workspace document manager — bootstraps pre-built .md templates
 * and provides read/write access to agent documents.
 *
 * ## Two-tier memory architecture (v0.15.0)
 *
 *   Mid-term (memory/memory_{date}.md)     Long-term (memory/memory.md)
 *   ───────────────────────────────       ────────────────────────────
 *   按日期分片, 每日/每次对话独立文件         单一文件, 永远精简
 *   自动追加: 对话摘要                      仅三种来源:
 *   agent.memory.record 写入                ① 用户指令 "请你记住"
 *   梦境分析的输入源                         ② Agent 自主判断重要
 *   可频繁读写, 旧文件可归档清理             ③ Dream 梦境整理产出
 *   NOT injected into system prompt         Injected into system prompt
 *
 * ## Template flow
 *
 * Templates live as real .md files in APK assets. At app startup,
 * [AgentTemplates.init] extracts them. When an agent is created, files
 * are copied from templates to the agent's workspace.
 */
object AgentDocs {

    @Volatile
    var bootstrapper: ((agentName: String, language: String) -> Unit)? = null

    /** Workspace doc change listeners — fired when Agent modifies workspace docs.
     *  PromptEngine uses this to invalidate cache; the shell uses it to refresh
     *  the live workspace file list.  @param agentName 被修改的 Agent 名称
     *  @param filePath 被修改文件的完整路径; null 表示未知 (兼容旧行为, 全量失效) */
    private val docListeners = java.util.concurrent.CopyOnWriteArrayList<(String, String?) -> Unit>()

    /** Register a workspace doc change listener. */
    fun addDocListener(listener: (agentName: String, filePath: String?) -> Unit) {
        docListeners.add(listener)
    }

    /** Remove a previously registered workspace doc change listener. */
    fun removeDocListener(listener: (agentName: String, filePath: String?) -> Unit) {
        docListeners.remove(listener)
    }

    /** Notify all listeners that an agent workspace document changed. */
    fun notifyDocChanged(agentName: String, filePath: String?) {
        docListeners.forEach { listener ->
            try { listener(agentName, filePath) }
            catch (_: Exception) { /* 单监听器失败不阻塞其余 */ }
        }
    }

    /**
     * 旧模板教学章节黑名单 — 判定"memory.md 仍是原样旧模板"用。
     * 真实记忆的 `## ` 标题是时间戳 (如 "2026-08-05 14:30"), 天然不在黑名单,
     * 因此"全部标题命中黑名单" ⇔ 文件从未被写入真实记忆, 永不误判迁移。
     */
    val TEMPLATE_HEADING_BLACKLIST = setOf(
        // zh 旧模板 (v0.30.0 前) 教学章节
        "这个文件是什么", "这里记什么", "示例", "怎么写入（用命令，别直接编辑文件）", "不记什么",
        // en 旧模板教学章节
        "What this file is", "What to record here", "Example",
        "How to write (use commands, don't edit files directly)", "What NOT to record"
    )

    /** Create default doc files for a new agent. */
    fun bootstrap(agentName: String, language: String = "zh") {
        val dir = File(DataPaths.AGENTS, agentName)
        if (!dir.exists()) dir.mkdirs()
        // FIX(自检报告 P1-4): 旧工作区迁移 — memory.md 仍是原样旧模板 (全部 ## 标题命中
        // 教学黑名单) 时, 覆盖为模板池新版 (瘦身模板)。幂等: 迁移后标题不再全黑名单, 自然跳过。
        migrateLegacyMemoryTemplate(agentName, language)
        // Ensure long-term memory directory exists — 幂等，老工作区升级后也补建
        File(dir, "memory").mkdirs()
        // Ensure Notes directory exists — 记忆之外的笔记 (如其他 Agent 知识信息)
        File(dir, "Notes").mkdirs()
        // modes.md 补种 — 斜杠命令模式菜单文档 (模板资产)。
        // 无条件幂等: modes.md 缺失时从模板资产原子复制; 已存在文件不覆盖。
        if (!File(dir, "modes.md").exists()) {
            try {
                var template = File(DataPaths.AGENT_TEMPLATES, "$language/modes.md")
                if (!template.exists()) template = File(DataPaths.AGENT_TEMPLATES, "zh/modes.md")
                if (template.exists()) {
                    val target = File(dir, "modes.md")
                    val tmpFile = File(dir, "modes.md.tmp")
                    tmpFile.writeText(template.readText())
                    try {
                        // 标准原子写: 覆盖式移动, 失败保留原文件 (此处目标本不存在)
                        java.nio.file.Files.move(
                            tmpFile.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                        KernelLog.i("AgentDocs", "migrate: seeded modes.md ($agentName)")
                    } catch (e: Exception) {
                        KernelLog.w("AgentDocs", "seed modes.md failed: ${e.message}")
                        try { tmpFile.delete() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                KernelLog.w("AgentDocs", "seed modes.md failed: ${e.message}")
            }
        }
        if (File(dir, "soul.md").exists()) return
        bootstrapper?.invoke(agentName, language)
    }

    /**
     * 旧模板迁移 — memory.md 的所有 `## ` 标题全部命中 [TEMPLATE_HEADING_BLACKLIST]
     * （即文件仍为原样旧模板形态, 零条真实记忆）→ 用模板池新版覆盖写。
     * 真实记忆标题 (时间戳) 不在黑名单, 文件一旦写过真实记忆永不触发。
     */
    private fun migrateLegacyMemoryTemplate(agentName: String, language: String) {
        try {
            val file = File(DataPaths.longTermMemoryFile(agentName))
            if (!file.exists()) return
            val headings = file.readText().lines().map { it.trim() }
                .filter { it.startsWith("## ") }
                .map { it.removePrefix("## ").trim() }
            if (headings.isEmpty()) return
            if (headings.all { it in TEMPLATE_HEADING_BLACKLIST }) {
                resetDoc(agentName, "memory/memory.md", language)
                KernelLog.i("AgentDocs", "migrate: legacy memory template → slim template ($agentName)")
            }
        } catch (e: Exception) {
            KernelLog.w("AgentDocs", "migrateLegacyMemoryTemplate failed: ${e.message}")
        }
    }

    /**
     * 重置工作区文档为 APK 预置版（模板覆盖写，区别于 bootstrap 的"只补缺失"）。
     * 模板路径 {BASE}/agent-templates/{language}/{relativePath}，language 模板缺失回退 zh。
     * @param relativePath 相对工作区根的路径 (如 "agents.md" / "memory/memory.md")
     * @return true = 已覆盖写回预置版; false = 模板不存在或写入失败 (原文件不被破坏)
     */
    fun resetDoc(agentName: String, relativePath: String, language: String = "zh"): Boolean {
        // 防路径穿越: 仅允许 .md 相对路径
        if (relativePath.contains("..") || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            KernelLog.w("AgentDocs", "resetDoc: unsafe relativePath rejected: $relativePath")
            return false
        }
        val target = File(File(DataPaths.AGENTS, agentName), relativePath)
        var template = File(DataPaths.AGENT_TEMPLATES, "$language/$relativePath")
        if (!template.exists()) template = File(DataPaths.AGENT_TEMPLATES, "zh/$relativePath")
        if (!template.exists()) {
            KernelLog.w("AgentDocs", "resetDoc: template missing for $relativePath ($agentName)")
            return false
        }
        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        return try {
            target.parentFile?.mkdirs()
            tmpFile.writeText(template.readText())
            // 标准原子写: Files.move 覆盖 (Windows 上 renameTo 无法覆盖已存在目标,
            // 旧"先删目标"写法在 rename 失败时会丢原文件)
            java.nio.file.Files.move(
                tmpFile.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            KernelLog.i("AgentDocs", "reset: $relativePath → built-in ($agentName)")
            notifyDocChanged(agentName, target.absolutePath)
            true
        } catch (e: Exception) {
            try { tmpFile.delete() } catch (_: Exception) {}
            KernelLog.w("AgentDocs", "resetDoc $relativePath failed: ${e.message}")
            false
        }
    }

    // ── Document readers ──────────────────────────────────────────

    fun readAgentsDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/agents.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readAgentsDoc"); ""
        } else ""
    }

    fun readProfileDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/profile.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readProfileDoc"); ""
        } else ""
    }

    fun readSoulDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/soul.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readSoulDoc"); ""
        } else ""
    }

    // ── Long-term memory (injected into system prompt) ────────────

    /** Read long-term memory — injected into every LLM system prompt. */
    suspend fun readLongTermMemoryAsync(agentName: String): String = withContext(com.mengpaw.kernel.KernelDispatchers.PROMPT_IO) {
        readLongTermMemory(agentName)
    }

    fun readLongTermMemory(agentName: String): String {
        val file = File(DataPaths.longTermMemoryFile(agentName))
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readLongTermMemory"); ""
        } else ""
    }

    /**
     * 长期记忆条目数 — 排除旧模板教学章节 (FIX(自检报告 P1-4): 此前裸数 ## 行,
     * 旧模板 5 个教学章节被数成"5 条记忆")。新瘦身模板无 ## 标题, 自然为 0。
     */
    fun countLongTermEntries(agentName: String): Int =
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
    fun appendLongTermMemory(agentName: String, entry: String) =
        appendLongTermMemory(agentName, entry, defaultMemoryTimestamp())

    /** 指定标题的长期记忆写入 (供 agent.memory.write 使用, 标题=ID)。 */
    fun appendLongTermMemory(agentName: String, entry: String, title: String) {
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
            notifyDocChanged(agentName, file.absolutePath)
        } catch (e: Exception) { KernelLog.w("AgentDocs", "tmpCleanup2: ${e.message}") }
    }

    private fun defaultMemoryTimestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    /** Search long-term memory by keywords. */
    fun searchLongTermMemory(agentName: String, keywords: List<String>): String {
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

    suspend fun readMidTermMemoryAsync(agentName: String): String = withContext(com.mengpaw.kernel.KernelDispatchers.PROMPT_IO) {
        readMidTermMemory(agentName)
    }

    /** Read all mid-term memory files for today (for dream processing). */
    fun readMidTermMemory(agentName: String): String = readMidTermMemoryDate(agentName, today())

    /** Read mid-term memory for a specific date. */
    fun readMidTermMemoryDate(agentName: String, date: String): String {
        val file = File(DataPaths.midTermMemoryFile(agentName, date))
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readMidTermMemory"); ""
        } else ""
    }

    /** List all mid-term memory date files, sorted newest first. */
    fun listMidTermDates(agentName: String): List<String> {
        val dir = File(DataPaths.midTermMemoryDir(agentName))
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("memory_") && it.name.endsWith(".md") && it.name != "memory.md" }
            ?.map { it.name.removePrefix("memory_").removeSuffix(".md") }
            ?.sortedDescending()
            ?: emptyList()
    }

    /**
     * Auto-append to today's mid-term memory file.
     * Conversation summaries, facts, observations — NOT injected into prompts.
     */
    /**
     * 将中期记忆条目加入写入队列 (立即返回, 不阻塞).
     * 实际落盘由 flushMidTermMemoryQueue() 在 LLM 响应返回后执行,
     * 利用 2-5 秒网络等待窗口消除 I/O 感知延迟.
     */
    fun appendMidTermMemory(agentName: String, entry: String) {
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
    fun flushMidTermMemoryQueue(): Int {
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
    fun searchMidTermMemory(agentName: String, keywords: List<String>): String {
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
    fun midTermStats(agentName: String): Map<String, Int> {
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
    fun saveProjectMemory(agentName: String, projectName: String, report: String) {
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
    fun readProjectMemory(agentName: String, projectName: String): String {
        val file = File(DataPaths.projectMemoryFile(agentName, projectName))
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readProjectMemory"); ""
        } else ""
    }

    /** Search across all project memories by keywords. */
    fun searchProjectMemory(agentName: String, keywords: List<String>): String {
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
    fun deleteEntry(agentName: String, filePath: String, entryId: String): Int {
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
    fun editEntry(agentName: String, filePath: String, entryId: String, newContent: String): Int {
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
    fun countMatchingEntries(filePath: String, entryId: String): Int {
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

    fun deleteLongTermEntry(agentName: String, entryId: String): Int =
        deleteEntry(agentName, DataPaths.longTermMemoryFile(agentName), entryId)

    fun editLongTermEntry(agentName: String, entryId: String, newContent: String): Int =
        editEntry(agentName, DataPaths.longTermMemoryFile(agentName), entryId, newContent)

    fun countLongTermMatches(agentName: String, entryId: String): Int =
        countMatchingEntries(DataPaths.longTermMemoryFile(agentName), entryId)

    // ── File-level deletion ───────────────────────────────────────

    fun deleteMidTermFile(agentName: String, date: String): Boolean {
        val file = File(DataPaths.midTermMemoryFile(agentName, date))
        return if (file.exists()) { file.delete() } else false
    }

    fun deleteProjectMemory(agentName: String, projectName: String): Boolean {
        val file = File(DataPaths.projectMemoryFile(agentName, projectName))
        return if (file.exists()) { file.delete() } else false
    }

    fun deleteBoost(agentName: String): Boolean {
        val file = File(DataPaths.AGENTS, "$agentName/boost.md")
        return if (file.exists()) { file.delete() } else false
    }

    /** Read boost.md — first-run bootstrap guidance file. Empty string means no boost needed. */
    fun readBoostDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/boost.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readBoostDoc"); ""
        } else ""
    }

    /** Read heartbeat.md — CRON task rules. Empty string means skip all scheduled tasks. */
    fun readHeartbeatDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/heartbeat.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readHeartbeatDoc"); ""
        } else ""
    }

    /** Read trumanshow.md — Truman Show (random chat) rules. Empty string = built-in topic pool only. */
    fun readTrumanShowDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/trumanshow.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readTrumanShowDoc"); ""
        } else ""
    }

    // ── Legacy compatibility (delegates to long-term for prompt injection) ──

    /** @deprecated Use [readLongTermMemory] for system prompts. */
    fun readMemoryDoc(agentName: String): String = readLongTermMemory(agentName)

    /** @deprecated Use [readLongTermMemoryAsync]. */
    suspend fun readMemoryDocAsync(agentName: String): String = readLongTermMemoryAsync(agentName)

    /**
     * @deprecated Use [searchLongTermMemory] or [searchMidTermMemory] explicitly.
     * Searches LONG-TERM memory only (what goes into prompts).
     */
    fun recallMemory(agentName: String, keywords: List<String>): String =
        searchLongTermMemory(agentName, keywords)

    /** @deprecated Use [appendMidTermMemory] for auto-recording. */
    fun appendMemory(agentName: String, entry: String) = appendMidTermMemory(agentName, entry)
}
