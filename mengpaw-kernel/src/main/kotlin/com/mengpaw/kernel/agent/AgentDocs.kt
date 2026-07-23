// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Agent workspace document manager — bootstraps pre-built .md templates
 * and provides read access to agent documents.
 *
 * ## Template flow
 *
 * Templates live as real .md files in APK assets (mengpaw-shell/assets/agent-templates/).
 * At app startup, [AgentTemplates.init] extracts them to a read-only path.
 * When an agent is created, files are copied from there to the agent's workspace.
 *
 * This object delegates the actual file-copy work to a [bootstrapper] callback
 * set by the Android layer — keeping the kernel module zero-Android-dependency.
 *
 * ## Customization
 *
 * Edit the .md files under mengpaw-shell/src/main/assets/agent-templates/zh/,
 * rebuild the APK. No Kotlin source changes needed.
 */
object AgentDocs {

    /**
     * Bootstrap callback set by the Android layer (AgentTemplates.bootstrapAgent).
     * When null (e.g. JVM tests), agent creation is a no-op beyond directory setup.
     */
    @Volatile
    var bootstrapper: ((agentName: String) -> Unit)? = null

    /** Create default doc files for a new agent by copying from pre-built templates. */
    fun bootstrap(agentName: String) {
        val dir = File(DataPaths.AGENTS, agentName)
        if (!dir.exists()) dir.mkdirs()
        // Fast path: if soul.md already exists, bootstrap is done
        if (File(dir, "soul.md").exists()) return
        // Delegate to Android-layer file copy (null-safe: JVM tests skip this)
        bootstrapper?.invoke(agentName)
    }

    /** Read the content of agents.md for a given agent. */
    fun readAgentsDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/agents.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readAgentsDoc"); ""
        } else ""
    }

    /** Read soul.md content. */
    fun readSoulDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/soul.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readSoulDoc"); ""
        } else ""
    }

    /** Read memory.md content — suspend 版本，在 IO 线程执行。 */
    suspend fun readMemoryDocAsync(agentName: String): String = withContext(Dispatchers.IO) {
        val file = File(DataPaths.AGENTS, "$agentName/memory.md")
        if (file.exists()) try { file.readText() } catch (e: Exception) { "" } else ""
    }

    /** Read memory.md content (同步，仅限已确认在后台线程的调用). */
    fun readMemoryDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/memory.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readMemoryDoc"); ""
        } else ""
    }

    /**
     * 跨会话召回 — 按关键词搜索 memory，只返回匹配的条目。
     * @param keywords 从用户消息中提取的关键词列表
     * @return 匹配的条目文本，若无匹配则返回空字符串
     */
    fun recallMemory(agentName: String, keywords: List<String>): String {
        val file = File(DataPaths.AGENTS, "$agentName/memory.md")
        if (!file.exists() || keywords.isEmpty()) return ""
        val content = try { file.readText() } catch (_: Exception) { return "" }
        if (content.isBlank()) return ""

        // 按 ## 标题分割为独立记忆条目
        val entries = content.split(Regex("(?=## )")).filter { it.isNotBlank() }
        val matched = entries.filter { entry ->
            keywords.any { kw -> entry.contains(kw, ignoreCase = true) }
        }
        return if (matched.isEmpty()) ""
        else "## 相关记忆\n\n${matched.joinToString("\n").trim()}"
    }

    /** 自动摘要 — 对话结束后追加一条记忆到 memory.md。 */
    fun appendMemory(agentName: String, entry: String) {
        try {
            val file = File(DataPaths.AGENTS, "$agentName/memory.md")
            file.parentFile?.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.getDefault()).format(java.util.Date())
            // 原子追加
            val line = "\n## $timestamp\n\n$entry\n"
            // 先读后写，确保不破坏已有内容
            val existing = if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(existing + line)
            tmp.renameTo(file)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }
}
