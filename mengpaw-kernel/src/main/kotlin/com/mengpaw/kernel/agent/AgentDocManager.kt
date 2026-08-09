// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.PluginManager
import java.io.File

/**
 * Manages the Agent's structured document system.
 *
 * Directory structure:
 *   /data/data/com.mengpaw/files/agents/{agent-id}/
 *   ├── Agents.md      # Security rules (system, read-only)
 *   ├── Soul.md        # Style & execution mode
 *   ├── Profile.md     # Identity & relationships
 *   ├── memory/        # Single-track memory: memory.md (long) + memory_{date}.md (mid) + project_*_memory.md
 *
 * v0.34.3: CLI.md 工作区文档删除 — 命令发现走 self.tools/self.search, agent.cli 为指引。
 */
class AgentDocManager(
    agentId: String = "agent-001",
    private val baseDir: String = com.mengpaw.kernel.DataPaths.AGENTS,
    /** Plugin manager (供 AgentExecutor 注入命令键集等). */
    @Volatile var pluginManager: PluginManager? = null
) {
    // FIX(自检报告 P0-2): 原硬编码 "agent-001" — 模板写入 {AGENTS}/{name}/ 而命令层读
    // {AGENTS}/agent-001/, 引导文件永不可见。生产会话经 AgentEngine.setAgentIdentity → bindAgent 绑定。
    @Volatile
    private var agentId: String = agentId

    /**
     * 实际注册的 agent.* 命令键 (AgentExecutor 构造时注入) — CLI.md agent 表按此
     * 运行时生成, 与实现永不漂移 (此前硬编码 8/40 行, Agent 查 agent.cli 看不到
     * read/write/ls 等 32 个命令 — 发现性铁律 v0.31.0)。
     */
    @Volatile
    var registeredAgentCommands: List<String> = emptyList()

    internal val agentDir: File get() = File(baseDir, agentId)

    /** 将文档系统绑定到指定 Agent 工作区目录（生产会话在 setAgentIdentity 时调用）。 */
    fun bindAgent(agentName: String) { agentId = agentName }

    // ── Initialization ────────────────────────────────────────────────

    /** Create all default documents for a new agent using pre-built .md templates. */
    fun initAgentDocs(profile: AgentProfile) {
        agentDir.mkdirs()

        // Copy all .md templates from assets via AgentDocs bootstrapper
        AgentDocs.bootstrap(profile.agentName)

        // Profile.md — always overwrite with dynamic identity (template is generic)
        val profileFile = file(AgentDocType.PROFILE)
        try { profileFile.atomicWriteText(profile.toMarkdown()) } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocManager.initAgentDocs")
        }

    }

    // ── Read ──────────────────────────────────────────────────────────

    fun getDoc(docType: AgentDocType): String {
        val f = file(docType)
        return if (f.exists()) try { f.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.getDoc"); "" } else ""
    }

    fun getDocPath(docType: AgentDocType): String = file(docType).absolutePath

    /**
     * 列出全部文档 (自检报告 P2-8): 每行带文件头 frontmatter 元数据 (summary / read_when),
     * 无 frontmatter 的文件退化为纯文件名。轻量: 只读文件头 2KB。
     * v0.34.3: 追加工作区进化档案目录 (evolution/) — 失败模式库/用户反应/框架反馈
     * 是工作区的一部分, agent.docs 应让 Agent 知道其存在; 无数据时不显示 (防空目录噪音)。
     */
    fun listDocs(): List<String> = buildList {
        addAll(AgentDocType.entries.map { docType ->
            val name = docType.name.lowercase() + ".md"
            val (summary, readWhen) = frontmatterOf(file(docType))
            when {
                summary == null -> name
                readWhen.isEmpty() -> "$name — $summary"
                else -> "$name — $summary [${readWhen.joinToString(" / ")}]"
            }
        })
        val evoDir = File(com.mengpaw.kernel.DataPaths.evolutionDir(agentId))
        if (evoDir.isDirectory && evoDir.listFiles()?.isNotEmpty() == true) {
            add("evolution/ — 进化档案: 失败模式库 (failures.jsonl, evolution.audit 查看) / 用户反应 (reactions.md) / 框架反馈 (feedback/)")
        }
    }

    /**
     * 提取 markdown 文件头 frontmatter (--- 包裹的 YAML 块, 前 2KB 内)。
     * 字段名照模板: summary (单行字符串) / read_when (缩进列表)。
     * @return (summary, read_when 列表) — 无 frontmatter 或解析失败时 summary 为 null。
     */
    private fun frontmatterOf(f: File): Pair<String?, List<String>> {
        if (!f.exists()) return null to emptyList()
        val head = try { f.readText().take(2048) } catch (_: Exception) { return null to emptyList() }
        if (!head.startsWith("---")) return null to emptyList()
        val closeIdx = head.indexOf("\n---", 3)
        if (closeIdx < 0) return null to emptyList()
        var summary: String? = null
        val readWhen = mutableListOf<String>()
        var inReadWhen = false
        head.substring(3, closeIdx).lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("summary:") ->
                    summary = t.removePrefix("summary:").trim().trim('"').trim('\'')
                t.startsWith("read_when:") -> inReadWhen = true
                t.startsWith("- ") && inReadWhen -> readWhen.add(t.removePrefix("- ").trim())
                t.isEmpty() -> { /* 空行不打断 read_when 块 */ }
                else -> inReadWhen = false
            }
        }
        return summary to readWhen
    }

    // ── CLI reference ─────────────────────────────────────────────────
    // v0.34.3: CLI.md 工作区文档删除 — 命令发现走 self.tools/self.search,
    // agent.cli 为轻量指引 (CliDocGenerator 一并移除)

    // ── Internal helpers ──────────────────────────────────────────────

    internal fun file(docType: AgentDocType): File = File(agentDir, docType.name.lowercase() + ".md")

    // ── Default document templates ────────────────────────────────────

    companion object {
        // v0.34.3: SELF/PLUGIN/AGENT/SECURITY 命令表删除 (P2-8 单一数据源),
        // CLI.md 工作区文档整体移除 — 命令发现走 self.tools/self.search。

        /** 浏览器协作能力 — readable by Agent via CLI (v0.22.1 重写: 真实三通道, 移除未接线的 45 命令手册). */
        val BROWSER_TOOLS_MD = AgentCliDocTables.BROWSER_TOOLS_MD
    }
}

/**
 * 标准原子写: 先写同目录 `.tmp`，再 Files.move(REPLACE_EXISTING) 覆盖。
 * 不再"先删目标再 rename" — 失败时原文件保持完好，残留 tmp 清理后向上抛
 * (调用方已各自 try/catch + ErrorCollector.report)。
 */
internal fun File.atomicWriteText(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    try {
        tmp.writeText(text)
        java.nio.file.Files.move(
            tmp.toPath(), this.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    } catch (e: Exception) {
        try { tmp.delete() } catch (_: Exception) {}
        throw e
    }
}
