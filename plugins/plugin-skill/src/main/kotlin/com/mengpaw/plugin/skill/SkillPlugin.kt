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
 *
 * ## Skill types
 * All skills are `.md` files with YAML frontmatter. The system supports:
 * - **Markdown 剧本型** — Agent reads the skill content and follows instructions
 * - **参数化型** — Skill content uses `{{key}}` placeholders; pass `key=value` via CLI
 * - **分类管理** — Skills grouped by category for filtering and discovery
 *
 * ## Commands
 * ```
 * skill.ls [--category <cat>]          列出技能（可按分类过滤）
 * skill.run <name> [key=value ...]      执行技能（支持参数替换）
 * skill.info <name>                    查看技能详情（元数据+内容预览）
 * skill.search <keyword>               按名称/描述搜索技能
 * skill.create <name> [options]        创建新技能模板
 * skill.enable <name>                  启用技能
 * skill.disable <name>                 停用技能
 * ```
 */
class SkillPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "skill-plugin",
        name = "技能系统",
        version = "0.2.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "全类型 Skill 引擎 — Markdown 剧本 + 参数化 + 分类过滤 + 搜索 + 管理",
        minCoreVersion = "0.6.2",
        commands = listOf(
            "skill.ls", "skill.run", "skill.info", "skill.search",
            "skill.create", "skill.enable", "skill.disable"
        )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "ls" to ::ls, "run" to ::run, "info" to ::info, "search" to ::search,
        "create" to ::create, "enable" to ::enable, "disable" to ::disable
    )

    // ═══════════════════════════════════════════════════════════════════
    // Skill categories (used for filtering and discovery)
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        val CATEGORIES = mapOf(
            "meta" to "元技能 — 管理/创建/沉淀 Skill 本身",
            "system" to "系统操作 — 配置、诊断、维护",
            "dev" to "开发 — 编码、调试、部署、代码审查",
            "office" to "办公 — 文档、表格、邮件、日程",
            "browser" to "浏览器 — 网页操控、数据采集、搜索",
            "general" to "通用 — 未分类的通用技能"
        )

        /** Human-readable label for a category key. */
        fun categoryLabel(cat: String): String = CATEGORIES[cat] ?: cat
    }

    // ═══════════════════════════════════════════════════════════════════

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
        val existing = d.listFiles { f -> f.extension == "md" }
        if (existing != null && existing.isNotEmpty()) return

        DEFAULT_SKILLS.forEach { (name, content) ->
            try { File(d, "$name.md").writeText(content) }
            catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.seedDefaults") }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLI Command implementations
    // ═══════════════════════════════════════════════════════════════════

    /** List skills, optionally filtered by category. */
    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        seedDefaults()
        var category: String? = null
        val rest = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--category", "-c" -> { if (i + 1 < args.size) category = args[++i] }
                else -> rest.add(args[i])
            }
            i++
        }

        val skills = listSkills(category = category)
        if (skills.isEmpty()) {
            val hint = if (category != null) " (分类: $category)" else ""
            return ExecutionResult.ok("(暂无技能$hint)\n\n使用 skill.create <name> 创建新技能。")
        }

        val catSummary = if (category != null) " [$category]" else ""
        return ExecutionResult.ok(buildString {
            appendLine("## 可用技能$catSummary (${skills.size})")
            appendLine()
            appendLine("| 状态 | 名称 | 分类 | 描述 |")
            appendLine("|------|------|------|------|")
            skills.forEach { s ->
                appendLine("| ${if (s.enabled) "✅" else "⛔"} | ${s.name} | ${s.category} | ${s.description.take(50)} |")
            }
            appendLine()
            appendLine("使用 skill.run <name> 执行，skill.info <name> 查看详情。")
        })
    }

    /**
     * Run a skill, loading its content as a prompt for the Agent.
     * Supports parameterized skills: `skill.run deploy target=prod env=staging`
     * replaces `{{target}}` and `{{env}}` in the skill content.
     */
    private suspend fun run(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: skill.run <name> [key=value ...]\n\n参数化 Skill 支持 {{key}} 占位符替换。",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )

        val name = args[0]
        val skill = getSkill(name)
            ?: return ExecutionResult.fail("Skill not found: $name\n使用 skill.ls 查看可用技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (!skill.enabled) return ExecutionResult.fail(
            "Skill disabled: $name\n使用 skill.enable $name 启用。",
            errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )

        // Parse key=value parameters from remaining args
        val params = mutableMapOf<String, String>()
        for (j in 1 until args.size) {
            val eq = args[j].indexOf('=')
            if (eq > 0) {
                params[args[j].substring(0, eq).trim()] = args[j].substring(eq + 1).trim()
            }
        }

        // Apply parameter substitution: {{key}} → value
        var content = skill.content
        params.forEach { (k, v) ->
            content = content.replace("{{$k}}", v)
            content = content.replace("{{${k.uppercase()}}}", v)
        }

        // Report unused parameters (warnings only, don't block execution)
        val unusedPlaceholders = Regex("\\{\\{(.+?)}}").findAll(content).map { it.groupValues[1] }.toList()
        val header = buildString {
            appendLine("## Skill: ${skill.name}")
            appendLine("描述: ${skill.description}")
            appendLine("分类: ${skill.category}")
            if (params.isNotEmpty()) {
                appendLine("参数: ${params.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
            if (unusedPlaceholders.isNotEmpty()) {
                appendLine("⚠ 未填参数: ${unusedPlaceholders.joinToString(", ")}")
            }
            appendLine()
            appendLine("---")
            appendLine()
        }

        return ExecutionResult.ok(header + content)
    }

    /** Show detailed info for a single skill. */
    private suspend fun info(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: skill.info <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val skill = getSkill(args[0])
            ?: return ExecutionResult.fail("Skill not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val placeholders = Regex("\\{\\{(.+?)}}").findAll(skill.content).map { it.groupValues[1] }.toList()
        return ExecutionResult.ok(buildString {
            appendLine("## ${skill.name}")
            appendLine()
            appendLine("| 属性 | 值 |")
            appendLine("|------|-----|")
            appendLine("| 名称 | ${skill.name} |")
            appendLine("| 描述 | ${skill.description} |")
            appendLine("| 分类 | ${skill.category} (${categoryLabel(skill.category)}) |")
            appendLine("| 状态 | ${if (skill.enabled) "已启用" else "已停用"} |")
            if (placeholders.isNotEmpty()) {
                appendLine("| 参数 | ${placeholders.joinToString(", ") { "`{{$it}}`" }} |")
            }
            appendLine()
            appendLine("### 内容预览")
            appendLine(skill.content.take(500))
            if (skill.content.length > 500) appendLine("\n... (${skill.content.length - 500} 字符省略)")
            if (placeholders.isNotEmpty()) {
                appendLine()
                appendLine("### 参数化调用示例")
                appendLine("```")
                appendLine("skill.run ${skill.name} ${placeholders.joinToString(" ") { "$it=<value>" }}")
                appendLine("```")
            }
        })
    }

    /** Search skills by name or description. */
    private suspend fun search(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: skill.search <keyword>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        seedDefaults()
        val q = args.joinToString(" ").lowercase()
        val results = listSkills().filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
        if (results.isEmpty()) return ExecutionResult.ok("未找到匹配 '$q' 的技能。\n使用 skill.ls 查看全部。")

        return ExecutionResult.ok(buildString {
            appendLine("## 搜索结果: '$q' (${results.size})")
            appendLine()
            results.forEach { s ->
                appendLine("- **${s.name}** [${s.category}] — ${s.description}")
            }
        })
    }

    /** Create a new skill template. */
    private suspend fun create(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: skill.create <name> [--category <cat>] [--description <desc>]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )

        val name = args[0]
        // Validate name
        if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return ExecutionResult.fail(
                "Skill 名称只能包含英文字母、数字、下划线和连字符。",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }

        // Parse options
        var category = "general"
        var description = ""
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--category", "-c" -> { if (i + 1 < args.size) { category = args[++i]; if (category !in CATEGORIES) category = "general" } }
                "--description", "-d" -> { if (i + 1 < args.size) description = args[++i] }
            }
            i++
        }
        if (description.isBlank()) description = "$name 技能"

        val file = File(dir, "$name.md")
        if (file.exists()) return ExecutionResult.fail(
            "Skill 已存在: $name\n使用 skill.run $name 执行，或 skill.info $name 查看。",
            errorCode = ErrorCodes.ERR_INTERNAL
        )

        val template = buildSkillTemplate(name, category, description)
        try {
            file.writeText(template)
            return ExecutionResult.ok(buildString {
                appendLine("✅ Skill '$name' 已创建。")
                appendLine()
                appendLine("| 属性 | 值 |")
                appendLine("|------|-----|")
                appendLine("| 分类 | $category |")
                appendLine("| 路径 | ${file.absolutePath} |")
                appendLine()
                appendLine("使用 skill.info $name 查看并编辑完善。")
            })
        } catch (e: Exception) {
            ErrorCollector.report(e, "SkillPlugin.create")
            return ExecutionResult.fail("创建失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun enable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: skill.enable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        setEnabled(args[0], true)
        return ExecutionResult.ok("Enabled: ${args[0]}")
    }

    private suspend fun disable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: skill.disable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        setEnabled(args[0], false)
        return ExecutionResult.ok("Disabled: ${args[0]}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Skill CRUD
    // ═══════════════════════════════════════════════════════════════════

    fun listSkills(category: String? = null): List<SkillDef> {
        val all = dir.listFiles { f -> f.extension == "md" }
            ?.mapNotNull { parseSkill(it) }?.sortedBy { it.name } ?: emptyList()
        return if (category != null) all.filter { it.category == category } else all
    }

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
        val text = try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "SkillPlugin.parseSkill"); return null
        }
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

    // ═══════════════════════════════════════════════════════════════════
    // Template generator
    // ═══════════════════════════════════════════════════════════════════

    private fun buildSkillTemplate(name: String, category: String, description: String): String {
        val sectionHints = when (category) {
            "dev" -> """
## 执行步骤
1. 分析代码结构（使用 `fs.ls` 和 `fs.cat` 查看相关文件）
2. 执行开发任务
3. 验证结果（编译/测试）
4. 汇报完成情况

## 注意事项
- 修改前备份重要文件
- 遵循项目代码规范
""".trimIndent()
            "office" -> """
## 执行步骤
1. 确认需求（文档类型、格式要求）
2. 使用 `fs.write` 或相关工具生成文档
3. 检查输出质量
4. 交付给用户确认

## 模板参数
使用 `{{参数名}}` 作为占位符，调用时传入 `skill.run $name 参数名=值`。
""".trimIndent()
            "browser" -> """
## 执行步骤
1. 使用浏览器命令打开目标页面
2. 执行数据采集/操作
3. 整理结果
4. 保存或汇报

## 浏览器命令参考
使用 `agent.browser-tools` 查看完整浏览器操控命令。
""".trimIndent()
            "system" -> """
## 执行步骤
1. 使用 `self.status` / `sys.*` 获取系统状态
2. 分析诊断信息
3. 执行维护操作
4. 记录结果到 `agent.memory.record`

## 安全注意
- 修改系统配置前需用户确认
- 使用 `agent.audit` 可回溯所有操作
""".trimIndent()
            "meta" -> """
## 执行步骤
1. 分析当前会话或目标
2. 制定 Skill 结构
3. 使用 `skill.create` 或 `fs.write` 写入技能文件
4. 使用 `skill.info` 验证

## Skill 设计原则
- 一个 Skill 只做一件事
- 描述清晰、步骤可执行
- 使用 `{{参数}}` 支持参数化
""".trimIndent()
            else -> """
## 执行步骤
1. 确认任务目标
2. 使用 `self.tools` 确认可用命令
3. 逐步执行
4. 汇报结果
""".trimIndent()
        }

        return """---
name: $name
description: $description
enabled: true
category: $category
---
# $name

$sectionHints
"""
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Data types
// ═══════════════════════════════════════════════════════════════════════

data class SkillDef(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val category: String,
    val content: String,
    val rawText: String = ""
)

// ═══════════════════════════════════════════════════════════════════════
// Default skills — adapted from QwenPaw
// ═══════════════════════════════════════════════════════════════════════

private val DEFAULT_SKILLS = mapOf(
    "make-skill" to """---
name: make-skill
description: 把当前工作流程沉淀为可复用的 Skill。触发词：「把这个变成 skill」「记住这个流程」「保存为技能」
enabled: true
category: meta
---
# Make Skill — 会话沉淀为可复用技能

把当前对话中的工作流、排错路径、配置步骤沉淀为 Skill。

## 两阶段流程

### Phase A：提出计划
1. 从对话中提炼核心流程（最多 5 个关键步骤）
2. 用自然语言向用户描述计划，等待确认

### Phase B：执行创建
1. 使用 `skill.create <name> --category <cat> --description <desc>` 创建技能模板
2. 使用 `fs.write` 补充完整技能内容（路径为 skill.info 显示的路径）
3. 使用 `skill.ls` 验证技能已创建
""",

    "make-plan" to """---
name: make-plan
description: 复杂任务分解；获取分步骤计划，由 Agent 自己逐步执行
enabled: true
category: meta
---
# Make Plan — 任务拆解与执行计划

当任务需要多步拆解、步骤之间有依赖关系时使用。

## 执行流程

### 1. 分析任务
- 用 `self.tools` 确认有哪些可用命令
- 用 `agent.memory` 查询相关经验

### 2. 制定计划
向用户输出执行计划表格：

| 步骤 | 操作 | 预期结果 | 状态 |
|------|------|---------|------|
| 1 | ... | ... | ⬜ |
| 2 | ... | ... | ⬜ |

确认后开始执行，每步标记完成状态。

### 3. 逐步执行
每步执行完汇报结果，失败时分析原因调整计划。

### 4. 完成汇报
汇总完成情况，标记遗留问题。
""",

    "guidance" to """---
name: guidance
description: 用户询问安装、配置、功能使用、报错排查时触发
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
description: 回答技术问题时快速定位要读的文档和源码路径
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
| Skills | `技能剧本/` 目录 |

## 约定
- 先读文档（`agent.memory`），再读源码（`fs.cat`）
- `agent.cli` 返回完整 CLI 参考
- 不确定时先 `fs.ls` 看目录结构
""",

    "daily-summary" to """---
name: daily-summary
description: 每日工作总结模板；检查 memory.md 并生成当日进展汇报
enabled: true
category: office
---
# 每日工作总结

## 执行步骤

1. 读取 `agent.memory` 查看近期记录
2. 筛选今日相关条目
3. 按以下模板生成汇报：

```
## {{date}} 工作总结

### 已完成
- ...

### 进行中
- ...

### 待跟进
- ...

### 备注
- ...
```

## 参数
- `{{date}}` — 日期，默认今天（格式: YYYY-MM-DD）
- `{{focus}}` — 可选的关注领域过滤
""",

    "plugin-auditor" to """---
name: plugin-auditor
description: 审查已安装插件状态，检查更新、安全风险、使用统计
enabled: true
category: system
---
# 插件审查

## 执行步骤

1. `plugin.list` — 获取所有已安装插件
2. 对每个 ACTIVE 插件执行 `plugin.info <id>`
3. `plugin.marketplace --refresh` — 拉取最新市场索引
4. 对每个插件执行 `plugin.update <id>` 检查更新
5. 汇总报告：

```
## 插件审查报告

| 插件 | 版本 | 状态 | 市场最新 | 安全风险 |
|------|------|------|---------|---------|
| ... | ... | ... | ... | ... |

### 建议操作
- 可更新: X 个
- 可清理: Y 个
- 风险项: Z 个
```
"""
)
