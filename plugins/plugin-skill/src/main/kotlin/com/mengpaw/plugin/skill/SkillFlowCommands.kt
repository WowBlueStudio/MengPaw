// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.skill

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 技能流转命令: 派生 (Project Memory → Skill) 与索取 (Agent → Agent)。
 *
 * - `skill.from.project <项目名>` — 读项目记忆 → LLM 提炼 (可流程化判定 + description 查重) →
 *   写入当前 Agent 本地技能池。产物自带 `## 进化目标` 三要素, 对齐 make_skills 线性进化定义。
 * - `skill.request <技能名> <来源Agent>` — 同设备 Agent 间技能复制; 冲突以简介为准, 不覆盖。
 * - `skill.ls --agent <Agent名>` — 浏览指定 Agent 本地技能 (可发现性)。
 */
internal object SkillFlowCommands {

    private const val STATUS_OK = "OK"
    private const val STATUS_NOT_FLOW = "NOT_FLOW"
    private const val STATUS_DUPLICATE = "DUPLICATE"

    /** 浏览指定 Agent 的本地技能 (Agent 间技能发现 — 配合 skill.request 索取)。 */
    suspend fun lsAgent(plugin: SkillPlugin, agentName: String, category: String?): ExecutionResult {
        val dir = File(DataPaths.agentSkillsDir(agentName))
        val skills = plugin.listSkills(dir, category)
        if (skills.isEmpty()) {
            val hint = if (category != null) " (分类: $category)" else ""
            return ExecutionResult.ok("(Agent '$agentName' 暂无本地技能$hint)\n可执行 skill.ls --local 查看自己的技能。")
        }
        return ExecutionResult.ok(buildString {
            appendLine("## Agent '$agentName' 本地技能 (${skills.size})")
            appendLine()
            appendLine("| 状态 | 名称 | 分类 | 描述 |")
            appendLine("|------|------|------|------|")
            skills.forEach { s ->
                appendLine("| ${if (s.enabled) "✅" else "⛔"} | ${s.name} | ${SkillPlugin.categoryLabel(s.category)} | ${s.description.take(50)} |")
            }
            appendLine()
            appendLine("需要某个技能: skill.request <技能名> $agentName 索取到本地。")
        })
    }

    /**
     * 从项目记忆派生技能。
     * LLM 一步完成: 可流程化判定 + description 语义查重 + 技能草稿生成 (含进化目标三要素)。
     */
    suspend fun fromProject(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.fail("Usage: skill.from.project <项目名>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val projectName = args[0]
        val agent = ctx.agentName ?: return ExecutionResult.fail("缺少 Agent 上下文。", errorCode = ErrorCodes.ERR_INTERNAL)
        val projectFile = projectMemoryFileSafe(agent, projectName)
            ?: return ExecutionResult.fail("非法项目名: $projectName (不能包含路径分隔符或穿越段)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!projectFile.exists()) {
            return ExecutionResult.fail("未找到项目记忆: $projectName\n使用 agent.memory.project 查看已有项目。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        }
        val memory = try {
            projectFile.readText()
        } catch (e: Exception) {
            ErrorCollector.report(e, "SkillPlugin.from.project")
            return ExecutionResult.fail("读取项目记忆失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        val llm = SkillPlugin.llmProvider
            ?: return ExecutionResult.fail("LLM 未就绪, 无法提炼技能。", errorCode = ErrorCodes.ERR_INTERNAL)

        val existing = (plugin.listSkills(plugin.localDir(agent)) + plugin.listSkills(plugin.globalDir)).distinctBy { it.name }
        val raw = try {
            llm.complete(buildFromProjectPrompt(projectName, memory, existing))
        } catch (e: Exception) {
            ErrorCollector.report(e, "SkillPlugin.from.project")
            return ExecutionResult.fail("技能提炼失败 (LLM 调用异常): ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        return materializeDraft(plugin, agent, projectName, raw)
    }

    /** 解析 LLM 输出并落盘 — internal 为测试可见性。 */
    internal fun materializeDraft(plugin: SkillPlugin, agent: String, projectName: String, raw: String): ExecutionResult {
        // 防御: 剥掉意外出现的代码围栏
        val text = raw.trim().let { t ->
            if (t.startsWith("```")) t.substringAfter('\n').trim().removeSuffix("```").trim() else t
        }
        if (text.isBlank()) {
            return ExecutionResult.fail("技能提炼失败: LLM 返回空结果。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        val firstLine = text.substringBefore('\n').trim().uppercase()
        return when {
            firstLine.startsWith(STATUS_NOT_FLOW) ->
                ExecutionResult.ok("项目记忆 '$projectName' 无可流程化内容, 未派生技能。")
            firstLine.startsWith(STATUS_DUPLICATE) -> {
                val dupName = text.substringAfter('\n').substringBefore('\n')
                    .removePrefix("已有技能:").trim().trim('`')
                ExecutionResult.fail(
                    "已有相似技能${if (dupName.isNotEmpty()) " '$dupName'" else ""} (以简介语义判断)。\n" +
                        "可先 skill.info $dupName 查看现有技能; 确需新建可换名后重试。",
                    errorCode = ErrorCodes.ERR_INTERNAL
                )
            }
            firstLine.startsWith(STATUS_OK) -> materializeOk(plugin, agent, text)
            else -> ExecutionResult.fail("技能提炼失败: LLM 输出格式异常 (缺少状态行)。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** 向其他 Agent 索取技能: 复制到本地, 冲突以 description 为准, 不覆盖。 */
    suspend fun request(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) {
            return ExecutionResult.fail("Usage: skill.request <技能名> <来源Agent>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val name = args[0]
        val srcAgent = args[1]
        val me = ctx.agentName ?: return ExecutionResult.fail("缺少 Agent 上下文。", errorCode = ErrorCodes.ERR_INTERNAL)
        if (srcAgent == me) {
            return ExecutionResult.fail("不能向自己索取技能。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val srcDir = File(DataPaths.agentSkillsDir(srcAgent))
        val srcFile = plugin.skillFile(srcDir, name)
            ?: return ExecutionResult.fail("非法技能名: $name (不能包含路径分隔符或穿越段)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!srcFile.exists()) {
            return ExecutionResult.fail("Agent '$srcAgent' 本地未找到 Skill: $name\n先用 skill.ls --agent $srcAgent 查看该 Agent 可用技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        }
        val src = plugin.parseSkill(srcFile)
            ?: return ExecutionResult.fail("技能解析失败: $name", errorCode = ErrorCodes.ERR_INTERNAL)
        val targetDir = plugin.localDir(me)
        val target = plugin.skillFile(targetDir, name)
            ?: return ExecutionResult.fail("非法技能名: $name", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (target.exists()) {
            return ExecutionResult.fail("本地已存在同名技能: $name\n可先 skill.info $name 查看, 确认不同再索取。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        // 查重以 description 为准 (标准化: 去空白 + 小写); 空简介不参与查重
        val norm = src.description.trim().lowercase()
        if (norm.isNotEmpty()) {
            val dup = plugin.listSkills(targetDir).firstOrNull { it.description.trim().lowercase() == norm }
            if (dup != null) {
                return ExecutionResult.fail(
                    "本地已有简介相同的技能: ${dup.name}\n(查重以简介为准, 避免标题措辞分歧造成重复技能)",
                    errorCode = ErrorCodes.ERR_INTERNAL
                )
            }
        }
        val stamped = stampSource(src.rawText.trimEnd(), srcAgent)
        return try {
            target.writeText(stamped)
            ExecutionResult.ok(
                "✅ 技能 '$name' 已从 Agent '$srcAgent' 索取到本地。\n\n" +
                    "| 属性 | 值 |\n|------|-----|\n| 路径 | ${target.absolutePath} |\n\n" +
                    "使用 skill.run $name 执行。如不再需要可 skill.rm $name。"
            )
        } catch (e: Exception) {
            ErrorCollector.report(e, "SkillPlugin.request")
            ExecutionResult.fail("技能写入失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    /** STATUS=OK 分支: 解析草稿 frontmatter → 校验 → 落盘。 */
    private fun materializeOk(plugin: SkillPlugin, agent: String, text: String): ExecutionResult {
        val skillText = text.substringAfter('\n').trim()
        val parsed = parseSkillText(skillText)
            ?: return ExecutionResult.fail("技能提炼失败: 产物缺少合法 frontmatter。", errorCode = ErrorCodes.ERR_INTERNAL)
        val name = parsed.name
        if (name.isBlank() || !name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return ExecutionResult.fail("技能提炼失败: LLM 生成的技能名非法 '$name' (仅允许字母/数字/下划线/连字符)。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        val category = if (parsed.category in SkillPlugin.CATEGORIES) parsed.category else "general"
        val target = plugin.skillFile(plugin.localDir(agent), name)
            ?: return ExecutionResult.fail("非法技能名: $name", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (target.exists()) {
            return ExecutionResult.fail("本地已存在同名技能: $name\n可先 skill.info $name 查看; 确需覆盖请用 agent.write 手动处理。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        val finalText = if (category == parsed.category) skillText else normalizeCategory(skillText, category)
        return try {
            target.writeText(finalText)
            ExecutionResult.ok(
                "✅ 技能已从项目记忆派生: $name\n\n" +
                    "| 属性 | 值 |\n|------|-----|\n| 路径 | ${target.absolutePath} |\n| 分类 | $category |\n\n" +
                    "使用 skill.run $name 自测。可编辑技能正文或 skill.push $name 共享到全局池。"
            )
        } catch (e: Exception) {
            ErrorCollector.report(e, "SkillPlugin.from.project")
            ExecutionResult.fail("技能写入失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** 项目名 → 项目记忆文件解析, canonicalPath 前缀校验防穿越 (projectName 直接拼文件名, 必须消毒)。 */
    private fun projectMemoryFileSafe(agent: String, projectName: String): File? {
        val dir = File(DataPaths.midTermMemoryDir(agent))
        val file = File(dir, "project_${projectName}_memory.md")
        return try {
            val root = dir.canonicalPath
            val target = file.canonicalPath
            if (target.startsWith("$root${File.separator}")) file else null
        } catch (_: Exception) { null }
    }

    /** 从文本解析技能 (LLM 产物), 与 SkillPlugin.parseSkill 同一 frontmatter 语义。 */
    private fun parseSkillText(text: String): SkillDef? {
        val fm = Regex("^---\\s*\n(.+?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text.trimStart()) ?: return null
        val frontmatter = fm.groupValues[1]
        val contentStart = fm.range.last + 1
        val content = text.substring(contentStart).trim()
        val props = frontmatter.lines().filter { it.isNotBlank() && it.contains(":") }.associate {
            val idx = it.indexOf(":"); it.take(idx).trim() to it.drop(idx + 1).trim()
        }
        return SkillDef(
            name = props["name"] ?: "",
            description = props["description"] ?: "",
            enabled = props["enabled"]?.toBooleanStrictOrNull() ?: true,
            category = props["category"] ?: "general",
            source = props["source"] ?: "",
            content = content,
            rawText = text
        )
    }

    /** 修正 category 后重写 frontmatter 的 category 行。 */
    private fun normalizeCategory(skillText: String, category: String): String =
        skillText.replace(Regex("(?m)^category:\\s*.*"), "category: $category")

    /** 索取时在技能末尾补来源标记 (可追溯)。 */
    private fun stampSource(content: String, srcAgent: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date())
        return if (content.contains("## 来源")) {
            content + "\n- 索取自 Agent `$srcAgent` ($ts)\n"
        } else {
            content + "\n\n## 来源\n- 索取自 Agent `$srcAgent` ($ts)\n"
        }
    }

    /** 派生提炼 prompt — internal 为测试可见性。 */
    internal fun buildFromProjectPrompt(projectName: String, memory: String, existing: List<SkillDef>): String = buildString {
        appendLine("你是一个技能提炼器。下面是一份项目记忆 (里程碑总结), 任务是判断其中是否含可复用的流程性经验, 并提炼为技能。")
        appendLine()
        appendLine("现有技能池 (供查重, 以 description 简介语义判断是否功能重叠; 标题措辞不同但功能相同也算重复):")
        if (existing.isEmpty()) appendLine("- (空)")
        else existing.forEach { appendLine("- ${it.name} — ${it.description}") }
        appendLine()
        appendLine("项目记忆内容:")
        appendLine(memory)
        appendLine()
        appendLine("输出格式 (严格, 只输出下列内容):")
        appendLine("第一行: <STATUS> — OK (含可流程化经验) / NOT_FLOW (仅事实记录, 无步骤/流程/验收标准) / DUPLICATE (与现有技能功能重叠)")
        appendLine("若 DUPLICATE, 第二行输出: 已有技能: <技能名>")
        appendLine("若 OK, 从第二行起输出完整技能 Markdown (不要代码围栏), 要求:")
        appendLine("- frontmatter: name (仅小写字母/数字/下划线/连字符)、description (一句话触发描述, 含适用场景)、enabled: true、category (从 meta/system/dev/office/browser/general 选)")
        appendLine("- 正文节: ## 适用场景 / ## 执行步骤 / ## 验证规则 / ## 来源 / ## 进化目标")
        appendLine("- ## 来源 写: project_$projectName · 项目记忆")
        appendLine("- ## 进化目标 三要素: 目标 (要进化成什么) / 稳定锚点 (核心步骤与约束, 升级不得破坏) / 收敛原则 (升级朝目标收敛, 目标外需求开新技能)")
        appendLine("- 规范: 完善 (步骤+验证规则, 其他 Agent 拿到可独立执行)、中性 (不掺个人偏好)、通用 (具体项目名/专名用 {{占位符}} 抽象)、无敏感 (不含 API Key/令牌/真实路径/个人信息)")
        appendLine("- 禁止添加 frontmatter 之外的额外字段")
    }
}
