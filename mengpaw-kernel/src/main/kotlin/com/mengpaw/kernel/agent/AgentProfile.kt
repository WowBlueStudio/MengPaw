// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * Agent identity and relationship profile.
 * Stored as Profile.md in the agent's document directory.
 */
data class AgentProfile(
    val agentId: String = "agent-001",
    val agentName: String = "檬爪助手",
    val userName: String = "用户",
    val language: String = "zh-CN",
    val maxSteps: Int = 50,
    val autoInstallPlugins: Boolean = false,
    val confirmDangerous: Boolean = true,
    // Extended fields for Agent Card display
    val name: String = "",       // display name (may differ from agentName)
    val position: String = "",   // role/position
    val bio: String = ""         // intro/description
) {
    fun toMarkdown(): String = """
# 关系设定

## 自身
- 名称: ${name.ifBlank { agentName }}
- ID: $agentId
- 定位: $position
- 简介: $bio
- 版本: 0.6.2

## 用户
- 称呼: $userName
- 关系: 协作者

## 协作 Agent
- (暂无) — 可通过 ACP 发现其他 Agent

## 配置
- 语言: $language
- 最大步数: $maxSteps
- 自动安装插件: ${if (autoInstallPlugins) "是" else "否（需确认）"}
- 危险操作确认: ${if (confirmDangerous) "是" else "否"}
""".trimIndent()

    companion object {
        fun fromMarkdown(markdown: String): AgentProfile {
            val lines = markdown.lines().associate {
                val parts = it.split(":", limit = 2)
                parts[0].trim().removePrefix("- ") to parts.getOrElse(1) { "" }.trim()
            }
            return AgentProfile(
                agentId = lines["ID"] ?: "agent-001",
                agentName = lines["名称"] ?: "檬爪助手",
                userName = lines["称呼"] ?: "用户",
                language = lines["语言"] ?: "zh-CN",
                maxSteps = lines["最大步数"]?.toIntOrNull() ?: 50,
                autoInstallPlugins = lines["自动安装插件"]?.contains("是") ?: false,
                confirmDangerous = lines["危险操作确认"]?.contains("是") ?: true,
                name = lines["名称"] ?: "",
                position = lines["定位"] ?: "",
                bio = lines["简介"] ?: ""
            )
        }

        /** Load agent profile from disk. */
        fun load(agentName: String): AgentProfile {
            return try {
                val file = File(DataPaths.AGENTS, "$agentName/profile.md")
                if (file.exists()) fromMarkdown(file.readText())
                else AgentProfile(agentName = agentName, name = agentName)
            } catch (e: Exception) {
                ErrorCollector.report(e, "AgentProfile.load")
                AgentProfile(agentName = agentName, name = agentName)
            }
        }

        /** Save agent profile to disk. */
        fun save(agentName: String, profile: AgentProfile) {
            try {
                val dir = File(DataPaths.AGENTS, agentName)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "profile.md")
                file.writeText(profile.toMarkdown())
            } catch (e: Exception) {
                ErrorCollector.report(e, "AgentProfile.save")
            }
        }
    }
}

/**
 * Agent document types managed by AgentDocManager.
 */
enum class AgentDocType {
    AGENTS,     // Security behavior rules (system-written, Agent read-only)
    SOUL,       // Agent style and execution mode (user-editable)
    PROFILE,    // Relationship settings (user-editable)
    MEMORY,     // Task records with auto-index (system-managed)
    CLI         // Framework + plugin command reference (auto-generated)
}

/**
 * A single memory record in the agent's Memory.md.
 */
data class MemoryRecord(
    val id: String,
    val date: String,
    val title: String,
    val keywords: List<String>,
    val content: String
)
