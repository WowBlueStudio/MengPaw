// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

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
 *
 * 实现按职责拆至 [AgentDocsListeners]/[AgentDocsBootstrap]/
 * [AgentDocsReaders]/[AgentDocsMemory] (400 行文件拆分) — 本对象保留全部
 * 公开成员签名, 逐成员委托。
 */
object AgentDocs {

    private val listeners = AgentDocsListeners()
    private val bootstrapImpl = AgentDocsBootstrap()
    private val readers = AgentDocsReaders()
    private val memory = AgentDocsMemory()

    var bootstrapper: ((agentName: String, language: String) -> Unit)?
        get() = bootstrapImpl.bootstrapper
        set(value) { bootstrapImpl.bootstrapper = value }

    /** Register a workspace doc change listener. */
    fun addDocListener(listener: (agentName: String, filePath: String?) -> Unit) {
        listeners.addDocListener(listener)
    }

    /** Remove a previously registered workspace doc change listener. */
    fun removeDocListener(listener: (agentName: String, filePath: String?) -> Unit) {
        listeners.removeDocListener(listener)
    }

    /** Notify all listeners that an agent workspace document changed. */
    fun notifyDocChanged(agentName: String, filePath: String?) {
        listeners.notifyDocChanged(agentName, filePath)
    }

    /**
     * 旧模板教学章节黑名单 — 判定"memory.md 仍是原样旧模板"用。
     * 真实记忆的 `## ` 标题是时间戳 (如 "2026-08-05 14:30"), 天然不在黑名单,
     * 因此"全部标题命中黑名单" ⇔ 文件从未被写入真实记忆, 永不误判迁移。
     */
    val TEMPLATE_HEADING_BLACKLIST: Set<String> get() = memory.TEMPLATE_HEADING_BLACKLIST

    /** Create default doc files for a new agent. */
    fun bootstrap(agentName: String, language: String = "zh") {
        bootstrapImpl.bootstrap(agentName, language)
    }

    /** 从 markdown 头部 frontmatter 提取 summary (P1-4 方案A: 系统提示词只注入 summary)。
     *  无 frontmatter / 无 summary 字段返回 null。 */
    fun frontmatterSummary(markdown: String): String? {
        val head = markdown.take(2048)
        if (!head.startsWith("---")) return null
        val closeIdx = head.indexOf("\n---", 3)
        if (closeIdx < 0) return null
        head.substring(3, closeIdx).lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("summary:")) {
                return t.removePrefix("summary:").trim().trim('"').trim('\'')
            }
        }
        return null
    }

    /**
     * 重置工作区文档为 APK 预置版（模板覆盖写，区别于 bootstrap 的"只补缺失"）。
     * 模板路径 {BASE}/agent-templates/{language}/{relativePath}，language 模板缺失回退 zh。
     * @param relativePath 相对工作区根的路径 (如 "agents.md" / "memory/memory.md")
     * @return true = 已覆盖写回预置版; false = 模板不存在或写入失败 (原文件不被破坏)
     */
    fun resetDoc(agentName: String, relativePath: String, language: String = "zh"): Boolean =
        bootstrapImpl.resetDoc(agentName, relativePath, language)

    // ── Document readers ──────────────────────────────────────────

    fun readAgentsDoc(agentName: String): String = readers.readAgentsDoc(agentName)

    fun readProfileDoc(agentName: String): String = readers.readProfileDoc(agentName)

    fun readSoulDoc(agentName: String): String = readers.readSoulDoc(agentName)

    /** Read boost.md — first-run bootstrap guidance file. Empty string means no boost needed. */
    fun readBoostDoc(agentName: String): String = readers.readBoostDoc(agentName)

    /** Read heartbeat.md — CRON task rules. Empty string means skip all scheduled tasks. */
    fun readHeartbeatDoc(agentName: String): String = readers.readHeartbeatDoc(agentName)

    /** Read trumanshow.md — Truman Show (random chat) rules. Empty string = built-in topic pool only. */
    fun readTrumanShowDoc(agentName: String): String = readers.readTrumanShowDoc(agentName)

    // ── Long-term memory (injected into system prompt) ────────────

    /** Read long-term memory — injected into every LLM system prompt. */
    suspend fun readLongTermMemoryAsync(agentName: String): String = memory.readLongTermMemoryAsync(agentName)

    fun readLongTermMemory(agentName: String): String = memory.readLongTermMemory(agentName)

    /**
     * 长期记忆条目数 — 排除旧模板教学章节 (FIX(自检报告 P1-4): 此前裸数 ## 行,
     * 旧模板 5 个教学章节被数成"5 条记忆")。新瘦身模板无 ## 标题, 自然为 0。
     */
    fun countLongTermEntries(agentName: String): Int = memory.countLongTermEntries(agentName)

    /**
     * Append to long-term memory — only for curated content.
     * Three valid sources:
     *   1. User says "请你记住"
     *   2. Agent self-judges as important/reusable
     *   3. Dream mode reorganization output
     */
    fun appendLongTermMemory(agentName: String, entry: String) = memory.appendLongTermMemory(agentName, entry)

    /** 指定标题的长期记忆写入 (供 agent.memory.write 使用, 标题=ID)。 */
    fun appendLongTermMemory(agentName: String, entry: String, title: String) =
        memory.appendLongTermMemory(agentName, entry, title)

    /** Search long-term memory by keywords. */
    fun searchLongTermMemory(agentName: String, keywords: List<String>): String =
        memory.searchLongTermMemory(agentName, keywords)

    // ── Mid-term memory (dated files, auto-recording, not in prompt) ──

    suspend fun readMidTermMemoryAsync(agentName: String): String = memory.readMidTermMemoryAsync(agentName)

    /** Read all mid-term memory files for today (for dream processing). */
    fun readMidTermMemory(agentName: String): String = memory.readMidTermMemory(agentName)

    /** Read mid-term memory for a specific date. */
    fun readMidTermMemoryDate(agentName: String, date: String): String =
        memory.readMidTermMemoryDate(agentName, date)

    /** List all mid-term memory date files, sorted newest first. */
    fun listMidTermDates(agentName: String): List<String> = memory.listMidTermDates(agentName)

    /**
     * 将中期记忆条目加入写入队列 (立即返回, 不阻塞).
     * 实际落盘由 flushMidTermMemoryQueue() 在 LLM 响应返回后执行,
     * 利用 2-5 秒网络等待窗口消除 I/O 感知延迟.
     */
    fun appendMidTermMemory(agentName: String, entry: String) = memory.appendMidTermMemory(agentName, entry)

    /**
     * 刷新中期记忆写入队列到磁盘.
     * 在 LLM 响应返回后调用, 利用已有等待窗口使 I/O 成本为零.
     * @return 成功落盘的条目数
     */
    fun flushMidTermMemoryQueue(): Int = memory.flushMidTermMemoryQueue()

    /** Search across all mid-term memory files by keywords. */
    fun searchMidTermMemory(agentName: String, keywords: List<String>): String =
        memory.searchMidTermMemory(agentName, keywords)

    /** Get mid-term memory stats: date → entry count. */
    fun midTermStats(agentName: String): Map<String, Int> = memory.midTermStats(agentName)

    // ── Project memory (reusable project completion patterns) ──────

    /**
     * Save a project completion report.
     * Called at milestones or project closure to capture reusable methodology.
     */
    fun saveProjectMemory(agentName: String, projectName: String, report: String) =
        memory.saveProjectMemory(agentName, projectName, report)

    /** Read a project memory file. */
    fun readProjectMemory(agentName: String, projectName: String): String =
        memory.readProjectMemory(agentName, projectName)

    /** Search across all project memories by keywords. */
    fun searchProjectMemory(agentName: String, keywords: List<String>): String =
        memory.searchProjectMemory(agentName, keywords)

    // ── Single-entry operations (universal for all memory files) ──

    /**
     * Delete a SINGLE entry from any memory file by exact ## header match.
     * Safety: refuses blank IDs, IDs < 10 chars, and ambiguous (multi-match) IDs.
     * @return Number of entries deleted (0 or 1).
     */
    fun deleteEntry(agentName: String, filePath: String, entryId: String): Int =
        memory.deleteEntry(agentName, filePath, entryId)

    /**
     * Replace a SINGLE entry's content in any memory file.
     * Finds the entry by exact ## header, replaces only its content (preserves header).
     * @return Number of entries edited (0 or 1).
     */
    fun editEntry(agentName: String, filePath: String, entryId: String, newContent: String): Int =
        memory.editEntry(agentName, filePath, entryId, newContent)

    /** Count entries matching an ID in a file. Returns -1 on error. */
    fun countMatchingEntries(filePath: String, entryId: String): Int =
        memory.countMatchingEntries(filePath, entryId)

    // ── Convenience wrappers (delegate to generic engine) ─────────

    fun deleteLongTermEntry(agentName: String, entryId: String): Int =
        memory.deleteLongTermEntry(agentName, entryId)

    fun editLongTermEntry(agentName: String, entryId: String, newContent: String): Int =
        memory.editLongTermEntry(agentName, entryId, newContent)

    fun countLongTermMatches(agentName: String, entryId: String): Int =
        memory.countLongTermMatches(agentName, entryId)

    // ── File-level deletion ───────────────────────────────────────

    fun deleteMidTermFile(agentName: String, date: String): Boolean =
        memory.deleteMidTermFile(agentName, date)

    fun deleteProjectMemory(agentName: String, projectName: String): Boolean =
        memory.deleteProjectMemory(agentName, projectName)

    fun deleteBoost(agentName: String): Boolean = memory.deleteBoost(agentName)

    // ── Legacy compatibility (delegates to long-term for prompt injection) ──

    /** @deprecated Use [readLongTermMemory] for system prompts. */
    fun readMemoryDoc(agentName: String): String = memory.readMemoryDoc(agentName)

    /** @deprecated Use [readLongTermMemoryAsync]. */
    suspend fun readMemoryDocAsync(agentName: String): String = memory.readMemoryDocAsync(agentName)

    /**
     * @deprecated Use [searchLongTermMemory] or [searchMidTermMemory] explicitly.
     * Searches LONG-TERM memory only (what goes into prompts).
     */
    fun recallMemory(agentName: String, keywords: List<String>): String =
        memory.recallMemory(agentName, keywords)

    /** @deprecated Use [appendMidTermMemory] for auto-recording. */
    fun appendMemory(agentName: String, entry: String) = memory.appendMemory(agentName, entry)
}
