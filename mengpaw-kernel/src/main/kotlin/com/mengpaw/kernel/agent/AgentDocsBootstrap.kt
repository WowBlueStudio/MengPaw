// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import java.io.File

/**
 * Agent 工作区引导/模板重置 — 拆自 AgentDocs (400 行文件拆分)。
 * bootstrap 补种缺失文档 + 旧模板迁移; resetDoc 模板覆盖写 (防路径穿越)。
 */
internal class AgentDocsBootstrap {

    @Volatile
    internal var bootstrapper: ((agentName: String, language: String) -> Unit)? = null

    /** Create default doc files for a new agent. */
    internal fun bootstrap(agentName: String, language: String = "zh") {
        val dir = File(DataPaths.AGENTS, agentName)
        if (!dir.exists()) dir.mkdirs()
        // FIX(自检报告 P1-4): 旧工作区迁移 — memory.md 仍是原样旧模板 (全部 ## 标题命中
        // 教学黑名单) 时, 覆盖为模板池新版 (瘦身模板)。幂等: 迁移后标题不再全黑名单, 自然跳过。
        migrateLegacyMemoryTemplate(agentName, language)
        // v0.34.4 Mission 并入 Swarm: 旧版 modes.md 含 /Mission 章节 (bootstrap 只补缺失不覆盖),
        // Agent 经 agent.modes 会看到已删除的命令 → 覆盖为模板新版。幂等: 迁移后无 /Mission, 自然跳过。
        migrateLegacyModesTemplate(agentName, language)
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
     * 旧模板迁移 — memory.md 的所有 `## ` 标题全部命中 [AgentDocs.TEMPLATE_HEADING_BLACKLIST]
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
            if (headings.all { it in AgentDocs.TEMPLATE_HEADING_BLACKLIST }) {
                resetDoc(agentName, "memory/memory.md", language)
                KernelLog.i("AgentDocs", "migrate: legacy memory template → slim template ($agentName)")
            }
        } catch (e: Exception) {
            KernelLog.w("AgentDocs", "migrateLegacyMemoryTemplate failed: ${e.message}")
        }
    }

    /** 旧模板迁移 — modes.md 含已删除章节 (`## /Mission` 旧 8 种版 / `## /Translate` v0.36 移除)
     *  → 覆盖为模板新版 (当前 6 种)。幂等: 迁移后无残留章节, 自然跳过。 */
    private fun migrateLegacyModesTemplate(agentName: String, language: String) {
        try {
            val file = File(File(DataPaths.AGENTS, agentName), "modes.md")
            if (!file.exists()) return
            val text = file.readText()
            if (text.contains("## /Mission") || text.contains("## /Translate")) {
                if (resetDoc(agentName, "modes.md", language)) {
                    KernelLog.i("AgentDocs", "migrate: legacy modes.md (Mission/Translate) → template ($agentName)")
                }
            }
        } catch (e: Exception) {
            KernelLog.w("AgentDocs", "migrateLegacyModesTemplate failed: ${e.message}")
        }
    }

    /**
     * 重置工作区文档为 APK 预置版（模板覆盖写，区别于 bootstrap 的"只补缺失"）。
     * 模板路径 {BASE}/agent-templates/{language}/{relativePath}，language 模板缺失回退 zh。
     * @param relativePath 相对工作区根的路径 (如 "agents.md" / "memory/memory.md")
     * @return true = 已覆盖写回预置版; false = 模板不存在或写入失败 (原文件不被破坏)
     */
    internal fun resetDoc(agentName: String, relativePath: String, language: String = "zh"): Boolean {
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
            AgentDocs.notifyDocChanged(agentName, target.absolutePath)
            true
        } catch (e: Exception) {
            try { tmpFile.delete() } catch (_: Exception) {}
            KernelLog.w("AgentDocs", "resetDoc $relativePath failed: ${e.message}")
            false
        }
    }
}
