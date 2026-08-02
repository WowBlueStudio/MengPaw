// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
 * ## Two-tier skill storage
 * - **Global pool** (`{BASE}/技能剧本/`): shared across all Agents, read-mostly.
 * - **Agent local** (`{BASE}/Agent文档/{name}/skills/`): per-Agent partition.
 *
 * `skill.run` checks local first, falls back to global. `skill.create` writes to local.
 * `skill.pull` copies from global to local; `skill.push` uploads from local to global.
 *
 * ## Commands
 * ```
 * skill.ls [--category <cat>] [--local]  列出技能（全局/本地，可按分类过滤）
 * skill.run <name> [key=value ...]        执行技能（先查本地再查全局）
 * skill.info <name>                       查看技能详情
 * skill.search <keyword>                  按名称/描述搜索（全局+本地）
 * skill.create <name> [options]           在 Agent 本地创建新技能
 * skill.pull <name>                       从全局池复制到 Agent 本地
 * skill.push <name>                       从 Agent 本地上传到全局池
 * skill.rm <name>                         删除 Agent 本地技能
 * skill.enable <name>                     启用技能
 * skill.disable <name>                    停用技能
 * ```
 */
class SkillPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "skill-plugin",
        name = "技能系统",
        version = "",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "双层 Skill 引擎 — 全局池+Agent本地，Markdown剧本+参数化+分类+搜索+管理",
        minCoreVersion = "0.6.2",
        commands = listOf(
            "skill.ls", "skill.run", "skill.info", "skill.search",
            "skill.create", "skill.rm", "skill.pull", "skill.push",
            "skill.enable", "skill.disable"
        )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "ls" to ::ls, "run" to ::run, "info" to ::info, "search" to ::search,
        "create" to ::create, "rm" to ::rm, "pull" to ::pull, "push" to ::push,
        "enable" to ::enable, "disable" to ::disable
    )

    companion object {
        val CATEGORIES = mapOf(
            "meta" to "元技能 — 管理/创建/沉淀 Skill 本身",
            "system" to "系统操作 — 配置、诊断、维护",
            "dev" to "开发 — 编码、调试、部署、代码审查",
            "office" to "办公 — 文档、表格、邮件、日程",
            "browser" to "浏览器 — 网页操控、数据采集、搜索",
            "general" to "通用 — 未分类的通用技能"
        )
        fun categoryLabel(cat: String): String = CATEGORIES[cat] ?: cat
    }

    private var storageDir = com.mengpaw.kernel.DataPaths.SKILLS
    private val globalDir: File get() = File(storageDir).also { it.mkdirs() }

    /** Agent's local skills dir — `{AGENTS}/{name}/skills/`. */
    private fun localDir(agentName: String): File =
        File(com.mengpaw.kernel.DataPaths.agentSkillsDir(agentName)).also { it.mkdirs() }

    override suspend fun onInstall(ctx: PluginContext) {
        // 全局池固定于 DataPaths.SKILLS（/技能剧本/）— UI/提示词/命令三方对齐。
        // 此前覆盖到插件私有目录（{ctx.storageDir}/skills）导致设置页全局技能恒空 —
        // 重大路径不一致修复: 不再覆盖, 保持初始值 + 存量迁移（移动不留残留）。
        globalDir
        seedDefaults()
        migrateLegacySkills(ctx)
    }

    /**
     * 存量迁移: 旧版把全局池放在 {ctx.storageDir}/skills（插件私有目录）—
     * 把其中的技能 .md 移动到 /技能剧本/（目标已存在则删源防残留），
     * 最后删除整个旧目录 — Android 存储宝贵, 零残留。
     */
    private fun migrateLegacySkills(ctx: PluginContext) {
        try {
            val legacy = java.io.File(ctx.storageDir, "skills")
            if (!legacy.isDirectory) return
            val target = java.io.File(storageDir).also { it.mkdirs() }
            legacy.listFiles { f -> f.isFile && f.extension == "md" }?.forEach { src ->
                val dest = java.io.File(target, src.name)
                try {
                    if (dest.exists()) {
                        src.delete()  // 目标已有同名 — 删源防残留
                    } else if (!src.renameTo(dest)) {
                        src.copyTo(dest, overwrite = true)
                        src.delete()
                    }
                } catch (_: Exception) { /* 单文件失败不阻塞其余 */ }
            }
            // 旧目录整体删除（含残留子项）— 零残留
            legacy.deleteRecursively()
        } catch (_: Exception) {}
    }

    /** Seed missing default skills into the global pool. */
    fun seedDefaults() {
        val d = globalDir
        val existing = d.listFiles { f -> f.extension == "md" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
        DEFAULT_SKILLS.forEach { (name, content) ->
            if (name !in existing) {
                try { File(d, "$name.md").writeText(content) }
                catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.seedDefaults") }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLI Command implementations
    // ═══════════════════════════════════════════════════════════════════

    /** List skills. Without --local: show global pool. With --local: show Agent local. */
    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        seedDefaults()
        var category: String? = null
        var localOnly = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--category", "-c" -> { if (i + 1 < args.size) category = args[++i] }
                "--local", "-l" -> localOnly = true
            }
            i++
        }

        val skills = if (localOnly) {
            listSkills(localDir(ctx.agentName ?: ""), category)
        } else {
            listSkills(globalDir, category)
        }

        val poolLabel = if (localOnly) "本地" else "全局"
        if (skills.isEmpty()) {
            val hint = if (category != null) " (分类: $category)" else ""
            return ExecutionResult.ok("(暂无${poolLabel}技能$hint)\n\n使用 skill.create <name> 创建新技能。")
        }

        return ExecutionResult.ok(buildString {
            appendLine("## ${poolLabel}可用技能 (${skills.size})")
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
     * Run a skill. Checks Agent local first, then global pool.
     */
    private suspend fun run(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: skill.run <name> [key=value ...]", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val name = args[0]
        var skill = parseSkill(File(localDir(ctx.agentName ?: ""), "$name.md"))
        if (skill == null) skill = parseSkill(File(globalDir, "$name.md"))
        if (skill == null) return ExecutionResult.fail(
            "Skill not found: $name\n本地和全局池均未找到。使用 skill.ls 查看全局池。", errorCode = ErrorCodes.ERR_NOT_FOUND
        )
        if (!skill.enabled) return ExecutionResult.fail(
            "Skill disabled: $name\n使用 skill.enable $name 启用。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )

        val params = mutableMapOf<String, String>()
        for (j in 1 until args.size) {
            val eq = args[j].indexOf('=')
            if (eq > 0) params[args[j].substring(0, eq).trim()] = args[j].substring(eq + 1).trim()
        }
        var content = skill.content
        params.forEach { (k, v) ->
            content = content.replace("{{$k}}", v)
            content = content.replace("{{${k.uppercase()}}}", v)
        }
        val unusedPlaceholders = Regex("\\{\\{(.+?)}}").findAll(content).map { it.groupValues[1] }.toList()
        val header = buildString {
            appendLine("## Skill: ${skill.name}")
            appendLine("描述: ${skill.description}")
            appendLine("分类: ${skill.category}")
            if (params.isNotEmpty()) appendLine("参数: ${params.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            if (unusedPlaceholders.isNotEmpty()) appendLine("⚠ 未填参数: ${unusedPlaceholders.joinToString(", ")}")
            appendLine(); appendLine("---"); appendLine()
        }
        return ExecutionResult.ok(header + content)
    }

    private suspend fun info(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.info <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        var skill = parseSkill(File(localDir(ctx.agentName ?: ""), "${args[0]}.md"))
        if (skill == null) skill = parseSkill(File(globalDir, "${args[0]}.md"))
        if (skill == null) return ExecutionResult.fail("Skill not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val placeholders = Regex("\\{\\{(.+?)}}").findAll(skill.content).map { it.groupValues[1] }.toList()
        return ExecutionResult.ok(buildString {
            appendLine("## ${skill.name}"); appendLine()
            appendLine("| 属性 | 值 |"); appendLine("|------|-----|")
            appendLine("| 名称 | ${skill.name} |")
            appendLine("| 描述 | ${skill.description} |")
            appendLine("| 分类 | ${skill.category} (${categoryLabel(skill.category)}) |")
            appendLine("| 状态 | ${if (skill.enabled) "已启用" else "已停用"} |")
            appendLine("| 位置 | ${if (File(localDir(ctx.agentName ?: ""), "${skill.name}.md").exists()) "Agent本地" else "全局池"} |")
            if (placeholders.isNotEmpty()) appendLine("| 参数 | ${placeholders.joinToString(", ") { "`{{$it}}`" }} |")
            appendLine(); appendLine("### 内容预览")
            appendLine(skill.content.take(500))
            if (skill.content.length > 500) appendLine("\n... (${skill.content.length - 500} 字符省略)")
        })
    }

    private suspend fun search(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.search <keyword>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        seedDefaults()
        val q = args.joinToString(" ").lowercase()
        val global = listSkills(globalDir).filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
        val local = listSkills(localDir(ctx.agentName ?: "")).filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
        val localNames = local.map { it.name }.toSet()
        if (global.isEmpty() && local.isEmpty()) return ExecutionResult.ok("未找到匹配 '$q' 的技能。\n使用 skill.ls 查看全局池。")
        return ExecutionResult.ok(buildString {
            if (local.isNotEmpty()) { appendLine("## Agent 本地匹配 (${local.size})"); local.forEach { s -> appendLine("- **${s.name}** [${s.category}] — ${s.description}") }; appendLine() }
            if (global.isNotEmpty()) { val f = global.filter { it.name !in localNames }; if (f.isNotEmpty()) { appendLine("## 全局池匹配 (${f.size})"); f.forEach { s -> appendLine("- **${s.name}** [${s.category}] — ${s.description}") }; appendLine(); appendLine("使用 skill.pull <name> 拉取到本地。") } }
        })
    }

    private suspend fun create(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.create <name> [--category <cat>] [--description <desc>]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) return ExecutionResult.fail("Skill 名称只能包含英文字母、数字、下划线和连字符。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        var category = "general"
        var description = ""
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--category", "-c" -> { if (i + 1 < args.size) { category = args[++i]; if (category !in CATEGORIES) category = "general" } }
                "--description", "-d" -> { if (i + 1 < args.size) description = args[++i] }
            }; i++
        }
        if (description.isBlank()) description = "$name 技能"
        val target = localDir(ctx.agentName ?: "")
        val file = File(target, "$name.md")
        if (file.exists()) return ExecutionResult.fail("本地 Skill 已存在: $name\n使用 skill.run $name 执行，或 skill.info $name 查看。", errorCode = ErrorCodes.ERR_INTERNAL)
        val template = buildSkillTemplate(name, category, description)
        return try {
            file.writeText(template)
            ExecutionResult.ok("✅ Skill '$name' 已创建到 Agent 本地。\n\n| 属性 | 值 |\n|------|-----|\n| 分类 | $category |\n| 路径 | ${file.absolutePath} |\n\n使用 skill.run $name 执行。使用 skill.push $name 上传到全局池。")
        } catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.create"); ExecutionResult.fail("创建失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun rm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.rm <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        val file = File(localDir(ctx.agentName ?: ""), "$name.md")
        if (!file.exists()) return ExecutionResult.fail("本地未找到 Skill: $name\n使用 skill.ls --local 查看本地技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return try { file.delete(); ExecutionResult.ok("Skill '$name' 已从本地删除。") }
        catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.rm"); ExecutionResult.fail("删除失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun pull(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.pull <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        val source = File(globalDir, "$name.md")
        if (!source.exists()) return ExecutionResult.fail("全局池中未找到 Skill: $name\n使用 skill.ls 查看全局可用技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val targetDir = localDir(ctx.agentName ?: ""); val target = File(targetDir, "$name.md")
        if (target.exists()) return ExecutionResult.ok("Skill '$name' 已在本地。使用 skill.run $name 执行。")
        return try { source.copyTo(target, overwrite = false); ExecutionResult.ok("Skill '$name' 已从全局池拉取到本地。\n使用 skill.run $name 执行。") }
        catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.pull"); ExecutionResult.fail("拉取失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun push(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.push <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        val source = File(localDir(ctx.agentName ?: ""), "$name.md")
        if (!source.exists()) return ExecutionResult.fail("本地未找到 Skill: $name\n使用 skill.ls --local 查看本地技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val target = File(globalDir, "$name.md"); val exists = target.exists()
        return try { source.copyTo(target, overwrite = true); val msg = if (exists) "已覆盖" else "已上传"; ExecutionResult.ok("Skill '$name' $msg 到全局池。\n现在所有 Agent 都可通过 skill.run $name 使用。") }
        catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.push"); ExecutionResult.fail("上传失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun enable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.enable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        setEnabled(args[0], true); return ExecutionResult.ok("Enabled: ${args[0]}")
    }

    private suspend fun disable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.disable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        setEnabled(args[0], false); return ExecutionResult.ok("Disabled: ${args[0]}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Skill CRUD
    // ═══════════════════════════════════════════════════════════════════

    private fun listSkills(dir: File, category: String? = null): List<SkillDef> {
        val all = dir.listFiles { f -> f.extension == "md" }?.mapNotNull { parseSkill(it) }?.sortedBy { it.name } ?: emptyList()
        return if (category != null) all.filter { it.category == category } else all
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val global = File(globalDir, "$name.md")
        if (global.exists()) {
            val text = try { global.readText() } catch (_: Exception) { return false }
            val newContent = text.replace(Regex("(?m)^enabled:\\s*(true|false)"), "enabled: $enabled")
            return try { global.writeText(newContent); true } catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.setEnabled"); false }
        }
        return false
    }

    private fun parseSkill(file: File): SkillDef? {
        if (!file.exists()) return null
        val text = try { file.readText() } catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.parseSkill"); return null }
        val fm = Regex("^---\\s*\n(.+?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text.trimStart())
        val frontmatter = fm?.groupValues?.get(1) ?: ""
        val contentStart = fm?.range?.last?.plus(1) ?: 0
        val content = text.substring(contentStart).trim()
        val props = frontmatter.lines().filter { it.isNotBlank() && it.contains(":") }.associate { val idx = it.indexOf(":"); it.take(idx).trim() to it.drop(idx + 1).trim() }
        return SkillDef(name = props["name"] ?: file.nameWithoutExtension, description = props["description"] ?: "", enabled = props["enabled"]?.toBooleanStrictOrNull() ?: true, category = props["category"] ?: "general", content = content, rawText = text)
    }

    private fun buildSkillTemplate(name: String, category: String, description: String): String {
        val hints = when (category) {
            "dev" -> "## 执行步骤\n1. 分析代码结构\n2. 执行开发任务\n3. 验证结果\n4. 汇报完成情况"
            "office" -> "## 执行步骤\n1. 确认需求\n2. 使用 agent.write 生成文档\n3. 检查输出质量\n4. 交付确认\n\n使用 {{param}} 占位符实现参数化。"
            "browser" -> "## 执行步骤\n1. 打开目标页面\n2. 数据采集/操作\n3. 整理结果\n4. 保存或汇报"
            "system" -> "## 执行步骤\n1. 使用 self.status 获取系统状态\n2. 分析诊断\n3. 执行维护\n4. 记录结果\n\n## 安全\n修改系统配置前需用户确认。"
            "meta" -> "## 执行步骤\n1. 分析目标\n2. 制定 Skill 结构\n3. 使用 skill.create 或 agent.write 写入\n4. 使用 skill.info 验证"
            else -> "## 执行步骤\n1. 确认任务目标\n2. 使用 self.tools 确认可用命令\n3. 逐步执行\n4. 汇报结果"
        }
        return "---\nname: $name\ndescription: $description\nenabled: true\ncategory: $category\n---\n# $name\n\n$hints\n"
    }
}

data class SkillDef(val name: String, val description: String, val enabled: Boolean, val category: String, val content: String, val rawText: String = "")

private val DEFAULT_SKILLS = mapOf(
    "make-plan" to "---\nname: make-plan\ndescription: 复杂任务分解；获取分步骤计划\nenabled: true\ncategory: meta\n---\n# Make Plan\n\n1. 用 self.tools 确认可用命令\n2. 制定计划表格\n3. 逐步执行\n4. 完成汇报",
    "guidance" to "---\nname: guidance\ndescription: 用户询问安装、配置、功能使用、报错排查时触发\nenabled: true\ncategory: system\n---\n# MengPaw 使用引导\n\n确定问题类型→查阅文档→无答案时建议查看 GitHub。",
    "plugin-index" to "---\nname: plugin-index\ndescription: 插件命令总索引\nenabled: true\ncategory: system\n---\n# 插件命令索引\n\nself/agent/plugin/sys 内置 + fs/net/tavily/hermes 等插件命名空间。\n使用 skill.run <name> 查看详细说明书。",
    "execution-modes" to "---\nname: execution-modes\ndescription: 四种执行模式详解\nenabled: true\ncategory: system\n---\n# 执行模式\n\n/Mission /Research /Translate /Silent\n用户可在输入框点 + 号选择。",
    "find_skills" to """
---
name: find_skills
description: 从外部技能市场（findskills.org + skills.sh）检索现成技能。触发词：「找找有没有技能」「搜索技能市场」「找现成的技能」
enabled: true
category: meta
---
# Find Skills — 外部技能检索

为当前任务从两个技能市场检索现成技能，避免重复造轮子。

## 检索流程

### Step 1 澄清需求
先明确两件事：
- **领域**：web / 测试 / 数据 / 设计 / DevOps / 文档 …
- **具体任务**：如「react 组件测试」而不是「测试」

### Step 2 查 skills.sh（先看热门）
skills.sh 是技能包官方排行榜（按安装量排序），先查它看主流方案：
- 有 Termux 环境：`skill.run termux` 获取桥接方式，然后执行 `npx skills find "关键词" [--owner 组织]`（如 `npx skills find "react testing"`）
- 无 Termux：跳过本步，直接走 Step 3（findskills 的 API 已聚合大部分生态）
- 关注结果中的：安装量排行（leaderboard）、官方来源（vercel-labs / anthropics / microsoft 等）

### Step 3 findskills.org API 检索（主路径，无 Node 依赖）
用 net.curl 直接调 findskills 的开放 API：
```
net.curl "https://findskills.org/api/v1/search?q=关键词"
```
- 结果含技能名称、描述、来源（GitHub/ClawHub/OpenClaw）、质量与安全评分
- 若 net.curl 失败或返回空：用 `sys.browser.open "https://findskills.org/?q=关键词"` 打开 Web 目录人工浏览
- 补充列表接口（需要时）：`https://findskills.org/api/v1/skills`（按分类过滤）

### Step 4 质量验证（安装前必做）
对 2-3 个候选技能逐项核验：
- **安装量** ≥ 1K 优先（skills.sh 排行榜为准）
- **来源信誉**：官方组织（vercel-labs / anthropics / microsoft）优先，个人仓库看星数
- **GitHub 星数** ≥ 100 优先
- 描述与当前任务匹配度（不要装"沾边"技能）

### Step 5 汇报与安装
向用户汇报对比结果：技能名 / 描述 / 安装量 / 来源 / 安装方式。
安装建议：
- Termux 环境：`npx skills add <owner/repo@skill> -g -y`
- 无 Node 环境：按技能说明手动落地 —— 若是 MengPaw Skill 格式，用 `skill.create <name> --category <cat> --description "<desc>"` 建骨架后 `agent.write` 写入内容，`skill.push <name>` 共享到全局池；若是 MCP/其他形态，用 `self.mcp connect` 或按文档接入

## 检索技巧
- 换词重查：一次没命中就换相邻词再查 2-3 次（「ui ux design」→「frontend design」）
- 组合词比单词准：「web scraping」优于「scraping」
- 跨市场互补：findskills 覆盖面广（94K+ 技能/MCP/插件），skills.sh 质量信号强（安装量）——两者交叉验证
- 装之前先确认该技能在 MengPaw 上怎么跑（是否有 Node/Termux 依赖），避免装完用不了

## 注意
- 检索结果可能来自第三方——不执行含不明来源脚本的技能，先 `agent.read` 审查内容再决定
- 技能安装是用户决策：先汇报方案等确认，不要直接安装
""".trimIndent(),
"make_skills" to """
---
name: make_skills
description: 按需设计三类技能，或把当前会话沉淀为技能 — 知识剧本类 / 剧本+脚本类 / 流程限定 Flow 类，创建后自动借用进化流程升级。触发词：「设计个技能」「做个技能」「把这事做成技能」「把这个变成 skill」「记住这个流程」「保存为技能」「沉淀这个会话」
enabled: true
category: meta
---
# Make Skills — 按需设计 / 会话沉淀技能

两个入口：
- **按需设计**：用户提出新需求 → 走三类选型设计
- **会话沉淀**：把当前对话中的工作流、排错路径、配置步骤沉淀为技能（触发词：「把这个变成 skill」「记住这个流程」「保存为技能」「沉淀这个会话」）

根据需求类型选对技能形态，创建后自动进入进化升级循环。

## 为什么技能需要稳定进化目标（树状 vs 线性）

- **Agent 的进化是树状的**：可以从插件 / Tools / Skills / soul.md 等多种载体落地——失败可以同时沉淀到多个方向，天然可开分支、可并行。
- **Skill 的进化是线性的**：一个技能文件沿单线演进，没有分支能力——一旦方向漂移，无法回到过去的版本线，只能手动重构。
- 因此每个技能在创建时**必须定义稳定进化目标**：它是技能进化的方向锚点，后续每次升级都对照它收敛，禁止目标外发散。
- 目标外的新需求 → **开新技能**（保持每个技能的线性纯净），不要塞进旧技能。

## 需求分析：三类技能选型

| 用户需求类型 | 技能类型 | 形态 |
|---|---|---|
| 知识/方法（怎么做某事、查什么、遵循什么规范） | **知识剧本类** | 纯 Markdown 剧本（知识 + 步骤） |
| 部分功能需要脚本自动化（批量处理、数据转换、定时操作） | **剧本+脚本类** | 剧本正文 + `## 脚本` 段（脚本落文件执行） |
| 必须严格限定操作流程（顺序不可乱、每步有检查点、有禁止项） | **流程 Flow 类** | 剧本 + `## 执行流程（必须按顺序）` + 检查点 + `## 禁止` |

判断要点：
- 只有"该怎么做" → 知识剧本类
- 有"要跑一段程序" → 剧本+脚本类
- 用户强调"必须/严禁/顺序不能错"或操作有安全风险 → Flow 类

## 设计流程

### Phase A：提出计划（先确认再动手）
1. 分析需求 → 选定技能类型 → 列出剧本结构（步骤大纲）
2. **定义稳定进化目标**（三要素，见下）——这是技能的生命线
3. 用自然语言向用户描述计划（含进化目标），等确认。**不要直接创建空白技能**

**进化目标三要素**（写入正文 `## 进化目标` 段）：
- **目标**：一句话——这个技能要进化成什么（覆盖场景 / 达到的质量）
- **稳定锚点**：什么不变——核心步骤、关键约束（升级时不得破坏）
- **收敛原则**：每次升级必须朝目标收敛；目标外的功能扩展 → 开新技能

### Phase B：创建（用户确认后）
1. `skill.create <name> --category meta --description "<一句话触发描述>"`（本地池建骨架）
2. `agent.write` 完善正文（见下方三类模板；触发词写进 description；进化目标写进 `## 进化目标` 段）
3. `skill.ls` 验证已创建 → `skill.run <name>` 自测一遍
4. 需要共享给所有 Agent：`skill.push <name>` 上传全局池
5. 汇报：技能名 / 类型 / 触发方式 / 进化目标 / 已验证

## 三类格式模板

### 知识剧本类
```markdown
---
name: <skill-name>
description: <一句话：什么时候触发>
enabled: true
category: general
---
# <标题>
## 适用场景
## 执行步骤
1. ...
## 注意事项
## 进化目标
- 目标: <这个技能要进化成什么>
- 稳定锚点: <核心步骤/关键约束, 升级不得破坏>
- 收敛原则: 升级朝目标收敛; 目标外需求开新技能
```

### 剧本+脚本类
```markdown
---
name: <skill-name>
description: <触发描述>
enabled: true
category: dev
---
# <标题>
## 执行步骤
1. 准备输入
2. 落地脚本（见下）
3. 执行并收集结果
4. 汇报
## 脚本
<脚本代码（bash/python…）>
## 执行方式
- 有 Termux：skill.run termux 获取桥接，脚本写入临时文件后执行
- 有 Root：root.exec 执行
- 无环境：向用户说明需要什么环境
## 进化目标
- 目标: <脚本覆盖的场景/自动化程度>
- 稳定锚点: <核心流程与输入输出契约>
- 收敛原则: 升级朝目标收敛; 新自动化需求开新技能
```
约定：脚本段代码先用 `agent.write` 落地到工作区/临时文件再执行；脚本必须处理错误输出与边界情况（空输入、缺文件）。

### 流程 Flow 类
```markdown
---
name: <skill-name>
description: <触发描述>
enabled: true
category: system
---
# <标题>
## 前置条件
## 执行流程（必须按顺序）
1. 第 1 步
   - [ ] 检查点：...
2. 第 2 步
   - [ ] 检查点：...
## 禁止
- 严禁跳过第 1 步直接执行第 2 步
- 严禁 ...
## 完成后
- 汇报结果与每步检查点状态
## 进化目标
- 目标: <流程覆盖的操作域/安全等级>
- 稳定锚点: <步骤顺序与禁止项, 升级不得破坏>
- 收敛原则: 强化约束朝目标收敛; 新操作域开新技能
```

## 示例（会话沉淀）

用户说「记住我是怎么查天气的」→ Agent 提炼流程为 skill：

```markdown
---
name: check-weather
description: 通过 wttr.in 查询天气
enabled: true
category: general
---
# 查天气
## 步骤
1. net.curl "wttr.in/{city}?format=3"
2. 将结果翻译为中文展示给用户
## 进化目标
- 目标: 覆盖主流城市天气查询
- 稳定锚点: wttr.in API 调用方式
- 收敛原则: 升级朝目标收敛; 新查询需求开新技能
```

## 进化升级（创建后自动借用进化流程）

技能在后续使用中失败时，自动走进化升级循环：
0. **对照进化目标（每次升级前必做）**：
   - 这次修改朝 `## 进化目标` 收敛吗？→ 是：继续
   - 碰了稳定锚点（核心步骤/关键约束）吗？→ 碰了：先和用户确认，是否目标本身要变
   - 是目标外的新功能吗？→ 是：**开新技能**，不要污染本技能的线性进化
1. **识别失败模式**：命令失败后会收到省察引导；也可用 `evolution.audit` 查看失败记录
2. **分类处置**：
   - 步骤缺失/顺序错 → 修订剧本步骤（Flow 类补检查点）
   - 脚本报错 → 修脚本、补边界情况（空输入/缺文件/超时）
   - 约束被绕过 → 强化 Flow 类的 `## 禁止` 段
   - 触发词不灵 → 改进 description 让技能更易被自然触发
3. **更新技能**：`agent.write` 修改技能文件 → `skill.push <name>` 同步全局池
4. **标记已修正**：`evolution.mark-corrected`（防止同一失败模式反复引导）
5. **沉淀教训**：`agent.memory.keep` 记录「技能 <name> 失败模式：…，已修正为：…」

> 原则一：技能是活的——每次失败都是升级机会；修正后立即 push 共享，别让其他 Agent 重复踩坑。
> 原则二：**线性进化的前提是方向锚定**——进化目标不随失败而改（除非用户明确要改目标本身），升级永远朝目标收敛；目标外需求开新技能，保持每个技能的线性纯净。

""".trimIndent()
)
