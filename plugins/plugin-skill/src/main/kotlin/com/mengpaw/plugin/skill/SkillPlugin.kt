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
import com.mengpaw.kernel.llm.LlmProvider
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
 * skill.from.project <项目名>              从项目记忆派生可复用技能 (LLM 提炼, 见 SkillFlowCommands)
 * skill.request <技能名> <来源Agent>       向其他 Agent 索取技能 (见 SkillFlowCommands)
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
            "skill.enable", "skill.disable", "skill.from.project", "skill.request"
        ),
        commandKeywords = mapOf(
            "from.project" to com.mengpaw.kernel.plugin.CommandKeywords(
                zh = listOf("从项目记忆派生技能", "提炼技能", "项目记忆转技能", "技能派生"),
                en = listOf("derive skill from project", "extract skill", "project to skill")
            ),
            "request" to com.mengpaw.kernel.plugin.CommandKeywords(
                zh = listOf("索取技能", "请求技能", "借用技能", "技能共享"),
                en = listOf("request skill", "borrow skill", "fetch skill")
            )
        )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "ls" to ::ls, "run" to ::run, "info" to ::info, "search" to ::search,
        "create" to { args, ctx -> SkillManageCommands.create(this, args, ctx) },
        "rm" to { args, ctx -> SkillManageCommands.rm(this, args, ctx) },
        "pull" to { args, ctx -> SkillManageCommands.pull(this, args, ctx) },
        "push" to { args, ctx -> SkillManageCommands.push(this, args, ctx) },
        "enable" to { args, ctx -> SkillManageCommands.enable(this, args, ctx) },
        "disable" to { args, ctx -> SkillManageCommands.disable(this, args, ctx) },
        "from.project" to { args, ctx -> SkillFlowCommands.fromProject(this, args, ctx) },
        "request" to { args, ctx -> SkillFlowCommands.request(this, args, ctx) }
    )

    companion object {
        /** LLM 注入 — 由 shell 在会话创建/切换时赋值 (与 TribePlugin 同模式)。 */
        @Volatile
        var llmProvider: LlmProvider? = null

        val CATEGORIES = mapOf(
            "meta" to "元技能 — 管理/创建/沉淀 Skill 本身",
            "system" to "系统操作 — 配置、诊断、维护",
            "dev" to "开发 — 编码、调试、部署、代码审查",
            "office" to "办公 — 文档、表格、邮件、日程",
            "browser" to "浏览器 — 网页操控、数据采集、搜索",
            "general" to "通用 — 未分类的通用技能"
        )
        fun categoryLabel(cat: String): String = CATEGORIES[cat] ?: cat

        /** 内置来源标签 — 预置技能(核心/插件)框架维护不可删除; 空 = 用户技能。 */
        fun sourceLabel(source: String): String = when (source) {
            "core" -> "核心"
            "plugin" -> "插件"
            else -> "用户"
        }
    }

    private var storageDir = com.mengpaw.kernel.DataPaths.SKILLS
    internal val globalDir: File get() = File(storageDir).also { it.mkdirs() }

    /** Agent's local skills dir — `{AGENTS}/{name}/skills/`. */
    internal fun localDir(agentName: String): File =
        File(com.mengpaw.kernel.DataPaths.agentSkillsDir(agentName)).also { it.mkdirs() }

    override suspend fun onInstall(ctx: PluginContext) {
        // 全局池固定于 DataPaths.SKILLS（/技能剧本/）— UI/提示词/命令三方对齐。
        // 内置技能种子由 SkillSeeds（shell 启动时从 assets/skills 同步）管理，
        // 不再硬编码在源码中；此处只做存量迁移（移动不留残留）。
        globalDir
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

    // ═══════════════════════════════════════════════════════════════════
    // CLI Command implementations
    // ═══════════════════════════════════════════════════════════════════

    /** List skills. Without --local: show global pool. With --local: show Agent local. */
    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        var category: String? = null
        var localOnly = false
        var agentFilter: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--category", "-c" -> { if (i + 1 < args.size) category = args[++i] }
                "--local", "-l" -> localOnly = true
                "--agent", "-a" -> { if (i + 1 < args.size) agentFilter = args[++i] }
            }
            i++
        }

        // 查看指定 Agent 的本地技能 (Agent 间技能发现 — 配合 skill.request 索取)
        if (agentFilter != null) {
            return SkillFlowCommands.lsAgent(this, agentFilter, category)
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
            appendLine("| 状态 | 名称 | 来源 | 分类 | 描述 |")
            appendLine("|------|------|------|------|------|")
            skills.forEach { s ->
                appendLine("| ${if (s.enabled) "✅" else "⛔"} | ${s.name} | ${sourceLabel(s.source)} | ${s.category} | ${s.description.take(50)} |")
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
        // P1 修复: 路径消毒 — 拒绝越过技能根目录的名称
        var skill = skillFile(localDir(ctx.agentName ?: ""), name)?.let { parseSkill(it) }
        if (skill == null) skill = skillFile(globalDir, name)?.let { parseSkill(it) }
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
        // P1 修复: 路径消毒 — 拒绝越过技能根目录的名称
        var skill = skillFile(localDir(ctx.agentName ?: ""), args[0])?.let { parseSkill(it) }
        if (skill == null) skill = skillFile(globalDir, args[0])?.let { parseSkill(it) }
        if (skill == null) return ExecutionResult.fail("Skill not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val placeholders = Regex("\\{\\{(.+?)}}").findAll(skill.content).map { it.groupValues[1] }.toList()
        return ExecutionResult.ok(buildString {
            appendLine("## ${skill.name}"); appendLine()
            appendLine("| 属性 | 值 |"); appendLine("|------|-----|")
            appendLine("| 名称 | ${skill.name} |")
            appendLine("| 描述 | ${skill.description} |")
            appendLine("| 来源 | ${sourceLabel(skill.source)} |")
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

    // ═══════════════════════════════════════════════════════════════════
    // Skill CRUD
    // ═══════════════════════════════════════════════════════════════════

    internal fun listSkills(dir: File, category: String? = null): List<SkillDef> {
        val all = dir.listFiles { f -> f.extension == "md" }?.mapNotNull { parseSkill(it) }?.sortedBy { it.name } ?: emptyList()
        return if (category != null) all.filter { it.category == category } else all
    }

    /**
     * P1 修复: 技能名 → 技能文件路径解析消毒。
     * canonicalPath 前缀校验 — 拒绝含 `..` 段/绝对路径越过技能根目录的路径,
     * 防止 agent 通过 skill.run/rm 等读删工作区外文件。
     * internal 为测试可见性 (路径消毒单测)。
     */
    internal fun skillFile(dir: File, name: String): File? {
        val file = File(dir, "$name.md")
        return try {
            val root = dir.canonicalPath
            val target = file.canonicalPath
            if (target.startsWith("$root${File.separator}")) file else null
        } catch (_: Exception) { null }
    }

    /** internal 为测试可见性 (frontmatter 解析单测)。 */
    internal fun parseSkill(file: File): SkillDef? {
        if (!file.exists()) return null
        val text = try { file.readText() } catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.parseSkill"); return null }
        val fm = Regex("^---\\s*\n(.+?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text.trimStart())
        val frontmatter = fm?.groupValues?.get(1) ?: ""
        val contentStart = fm?.range?.last?.plus(1) ?: 0
        val content = text.substring(contentStart).trim()
        val props = frontmatter.lines().filter { it.isNotBlank() && it.contains(":") }.associate { val idx = it.indexOf(":"); it.take(idx).trim() to it.drop(idx + 1).trim() }
        return SkillDef(name = props["name"] ?: file.nameWithoutExtension, description = props["description"] ?: "", enabled = props["enabled"]?.toBooleanStrictOrNull() ?: true, category = props["category"] ?: "general", source = props["source"] ?: "", content = content, rawText = text)
    }

}

/**
 * @param source 内置来源标记 — "core"=框架核心(不可删) / "plugin"=插件附带(不可删) /
 *                "" = 用户自建/Agent 进化/后续新注册(可删)。由资产 frontmatter `source:` 声明。
 */
data class SkillDef(val name: String, val description: String, val enabled: Boolean, val category: String, val content: String, val rawText: String = "", val source: String = "")
