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

