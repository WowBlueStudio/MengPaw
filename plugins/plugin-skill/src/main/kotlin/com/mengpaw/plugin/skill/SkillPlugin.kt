// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.skill

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * Skill system plugin — provides skill.* CLI commands.
 * Each skill is a .md file with YAML frontmatter.
 */
class SkillPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "skill-plugin",
        name = "技能系统",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "可复用的 Agent 剧本系统（YAML+Markdown），含 3 个默认 Skill",
        minCoreVersion = "0.2.0",
        commands = listOf("skill.ls", "skill.run", "skill.enable", "skill.disable")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "ls" to ::ls, "run" to ::run, "enable" to ::enable, "disable" to ::disable
    )

    private var storageDir = com.mengpaw.kernel.DataPaths.SKILLS

    override suspend fun onInstall(ctx: PluginContext) {
        storageDir = "${ctx.storageDir}/skills"
        File(storageDir).mkdirs()
        seedDefaults()
    }

    private val dir: File get() = File(storageDir).also { it.mkdirs() }

    /** Create default skills on first run if the skills directory is empty. */
    fun seedDefaults() {
        val d = dir
        // Only seed if no skills exist yet (preserves user-created skills)
        val existing = d.listFiles { f -> f.extension == "md" }
        if (existing != null && existing.isNotEmpty()) return

        DEFAULT_SKILLS.forEach { (name, content) ->
            try { File(d, "$name.md").writeText(content) }
            catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.seedDefaults") }
        }
    }

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        seedDefaults()  // auto-seed on first access
        val skills = listSkills()
        if (skills.isEmpty()) return ExecutionResult.ok("(No skills installed)")
        return ExecutionResult.ok(skills.joinToString("\n") {
            "${if (it.enabled) "✅" else "⛔"} ${it.name} — ${it.description}"
        })
    }

    private suspend fun run(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.run <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val skill = getSkill(args[0]) ?: return ExecutionResult.fail("Skill not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (!skill.enabled) return ExecutionResult.fail("Skill disabled: ${args[0]}", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        return ExecutionResult.ok(skill.content)
    }

    private suspend fun enable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.enable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        setEnabled(args[0], true)
        return ExecutionResult.ok("Enabled: ${args[0]}")
    }

    private suspend fun disable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.disable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        setEnabled(args[0], false)
        return ExecutionResult.ok("Disabled: ${args[0]}")
    }

    // ── Skill CRUD ────────────────────────────────────────────────────

    fun listSkills(): List<SkillDef> = dir.listFiles { f -> f.extension == "md" }
        ?.mapNotNull { parseSkill(it) }?.sortedBy { it.name } ?: emptyList()

    fun getSkill(name: String): SkillDef? {
        val file = File(dir, "$name.md")
        return if (file.exists()) parseSkill(file) else null
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val skill = getSkill(name) ?: return false
        val newContent = skill.rawText.replace(
            Regex("(?m)^enabled:\\s*(true|false)"),
            "enabled: $enabled"
        )
        return try {
            File(dir, "$name.md").writeText(newContent)
            true
        } catch (e: Exception) {
            ErrorCollector.report(e, "SkillPlugin.setEnabled")
            false
        }
    }

    private fun parseSkill(file: File): SkillDef? {
        if (!file.exists()) return null
        val text = try { file.readText() } catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.parseSkill"); return null }
        val fm = Regex("^---\\s*\n(.+?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text.trimStart())
        val frontmatter = fm?.groupValues?.get(1) ?: ""
        val contentStart = fm?.range?.last?.plus(1) ?: 0
        val content = text.substring(contentStart).trim()
        val props = frontmatter.lines().filter { it.isNotBlank() && it.contains(":") }.associate {
            val idx = it.indexOf(":"); it.take(idx).trim() to it.drop(idx + 1).trim()
        }
        return SkillDef(
            name = props["name"] ?: file.nameWithoutExtension,
            description = props["description"] ?: "",
            enabled = props["enabled"]?.toBooleanStrictOrNull() ?: true,
            category = props["category"] ?: "general",
            content = content,
            rawText = text
        )
    }
}

data class SkillDef(
    val name: String, val description: String, val enabled: Boolean,
    val category: String, val content: String, val rawText: String = ""
)

/** Default skills seeded on first run — ported & adapted from QwenPaw. */
private val DEFAULT_SKILLS = mapOf(
    "make-skill" to """---
name: make-skill
description: 把当前会话沉淀为可复用的 skill。触发词：「把这个变成 skill」「记住这个流程」「保存为技能」
enabled: true
category: meta
---
# Make Skill — 会话沉淀为可复用技能

把当前对话中的工作流、排错路径、配置步骤沉淀为 skill。

## 两阶段流程

### Phase A：提出计划
1. 从对话中提炼核心流程（最多 5 个关键步骤）
2. 用自然语言向用户描述计划，等待确认

### Phase B：执行创建
1. 使用 `fs.write` 将技能内容写入技能目录
2. 技能文件格式参考本文件的 YAML frontmatter + Markdown 正文
3. 使用 `skill.ls` 验证技能已创建

## Skill 文件格式
` + "```" + `markdown
---
name: <skill-name>
description: <一句话描述>
enabled: true
category: <general|dev|office|browser|system>
---
# <技能标题>
## 执行步骤
1. ...
2. ...
` + "```" + `
""",

    "make-plan" to """---
name: make-plan
description: 需要多步拆解复杂任务时触发；获取分步骤执行计划，由 Agent 自己逐步执行
enabled: true
category: meta
---
# Make Plan — 任务拆解与计划

当任务需要多步拆解、步骤之间有依赖关系时使用。

## 执行流程

### 1. 分析任务
- 用 `self.tools` 确认有哪些可用命令
- 用 `agent.memory` 查询相关经验

### 2. 制定计划
向用户输出执行计划表格，确认后开始执行。

### 3. 逐步执行
每步执行完汇报结果，失败时分析原因调整计划。

### 4. 完成汇报
汇总完成情况，标记遗留问题。
""",

    "guidance" to """---
name: guidance
description: 用户询问安装、配置、或「怎么用」「报错了」时触发；帮助定位文档和排查问题
enabled: true
category: system
---
# MengPaw 使用引导

当用户询问安装、配置、功能使用、报错排查时使用。

## 标准流程

### 1. 确定问题类型
| 关键词 | 查阅内容 |
|--------|---------|
| 安装、APK | `agent.memory search 安装` |
| API Key、模型 | `agent.memory search API` |
| 插件、命令 | `self.tools` → `plugin.list` |
| 报错、闪退 | `agent.audit` |

### 2. 查阅文档
使用 `agent.memory [query]` 搜索已有记忆和文档。

### 3. 如果本地无答案
建议查看 https://github.com/WowBlueStudio/MengPaw
""",

    "source-index" to """---
name: source-index
description: 回答技术问题时快速定位要读的文档和源码；减少盲目搜索
enabled: true
category: system
---
# 文档与源码速查

## 关键词 → 源码路径
| 关键词 | 源码路径 |
|--------|---------|
| CLI、命令 | `mengpaw-kernel/.../cli/` |
| LLM、模型 | `mengpaw-kernel/.../llm/` |
| 安全 | `mengpaw-kernel/.../security/` |
| 插件 | `mengpaw-kernel/.../plugin/` |
| UI、设置 | `mengpaw-shell/.../ui/screens/` |
| 浏览器 | `mengpaw-browser/.../` |

## 约定
- 先读文档（`agent.memory`），再读源码（`fs.cat`）
- `agent.cli` 返回完整 CLI 参考
- 不确定时先 `fs.ls` 看目录结构
"""
)
