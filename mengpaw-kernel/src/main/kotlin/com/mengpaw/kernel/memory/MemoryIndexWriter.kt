// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.AgentDocs
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 记忆索引导出 (P3, docs/memory-git-sync-design.md §6.4) — `{agent}/memory/index.md`。
 *
 * 索引是**生成物**: 项目分组 + 每组条目/未归纳计数 + 最近 3 条标题。
 * 内容只由账本 (条目/摘要) 与工作区文件 (块标题) 决定, 因此两端同账本必得同文本 —
 * 可安全随工作区同步, 不会因生成时间不同而互相冲突。
 */
object MemoryIndexWriter {

    const val INDEX_REL_PATH = "memory/index.md"
    private const val TITLES_PER_GROUP = 3
    private const val TITLE_MAX_CHARS = 80
    private const val UNGROUPED = "未归类（中期）"
    private const val HEADER = "# 自动生成，勿手改 — memory/index.md"

    fun indexFile(agentName: String): File =
        File(File(DataPaths.resolver.agentDir(agentName)), INDEX_REL_PATH.replace('/', File.separatorChar))

    /** 读取索引原文 (缺失/损坏 → 空串)。 */
    fun read(agentName: String): String {
        val file = indexFile(agentName)
        if (!file.isFile) return ""
        return try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryIndexWriter.read")
            ""
        }
    }

    /** 渲染索引文本 (纯函数, 不落盘; 测试可直接断言)。 */
    fun render(agentName: String): String {
        val all = MemoryLedger.entries(agentName)
        val visible = all.filter { it.kind != MemoryLedger.KIND_DIGEST }
        val digested = MemoryLedger.digestedIds(all)
        val titleCache = HashMap<String, Map<String, String>>()

        fun titleOf(entry: MemoryLedger.Entry): String {
            val titles = titleCache.getOrPut(entry.file) { titlesOf(agentName, entry.file) }
            val title = titles[entry.blockHash].orEmpty().ifBlank { entry.blockHash }
            return if (title.length > TITLE_MAX_CHARS) title.take(TITLE_MAX_CHARS) + "…" else title
        }

        val grouped = visible.groupBy { it.project.trim().ifBlank { UNGROUPED } }
        val projectKeys = grouped.keys.filter { it != UNGROUPED }.sorted()
        val orderedKeys = projectKeys + if (grouped.containsKey(UNGROUPED)) listOf(UNGROUPED) else emptyList()

        return buildString {
            appendLine(HEADER)
            appendLine("> 条目: ${visible.size} | 未归纳: ${visible.count { it.entryId !in digested }}")
            appendLine()
            if (visible.isEmpty()) {
                appendLine("(暂无账本条目)")
                return@buildString
            }
            for (key in orderedKeys) {
                val entries = grouped[key].orEmpty()
                val recent = entries.sortedWith(
                    compareByDescending<MemoryLedger.Entry> { it.wall }.thenByDescending { it.entryId }
                ).take(TITLES_PER_GROUP)
                appendLine("## ${if (key == UNGROUPED) key else "项目: $key"}")
                appendLine("- 条目: ${entries.size} | 未归纳: ${entries.count { it.entryId !in digested }}")
                recent.forEach { appendLine("- ${it.wall} · ${titleOf(it)}") }
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    /**
     * 重建索引并原子落盘; 内容未变化时不重写 (避免无谓的 mtime 抖动)。
     * 写入后通知文档变更, 使提示词缓存立即失效。失败 → null (不抛)。
     */
    @Synchronized
    fun rebuild(agentName: String): String? {
        return try {
            val content = render(agentName)
            val target = indexFile(agentName)
            if (target.isFile && target.readText() == content) return content
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "index.md.tmp")
            try {
                tmp.writeText(content)
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                if (tmp.exists()) {
                    try { tmp.delete() } catch (_: Exception) { /* best-effort */ }
                }
            }
            try { AgentDocs.notifyDocChanged(agentName, target.absolutePath) } catch (_: Exception) { /* 监听器异常不影响索引 */ }
            content
        } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryIndexWriter.rebuild")
            null
        }
    }

    /** 读工作区文件 → `blockHash → 标题`; 文件缺失/损坏 → 空表。 */
    private fun titlesOf(agentName: String, relPath: String): Map<String, String> {
        val target = File(File(DataPaths.resolver.agentDir(agentName)), relPath.replace('/', File.separatorChar))
        if (!target.isFile) return emptyMap()
        val text = try { target.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryIndexWriter.titles")
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        MemoryLedger.splitBlocks(text).forEach { block ->
            out[MemoryLedger.blockHash(block)] = MemoryLedger.titleOf(block)
        }
        return out
    }
}
