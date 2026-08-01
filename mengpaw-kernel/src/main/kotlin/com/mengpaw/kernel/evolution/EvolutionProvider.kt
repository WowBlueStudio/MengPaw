// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.CommandIndex
import com.mengpaw.kernel.cli.CommandSearch
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.namespace.NotifyBus
import java.io.File

/**
 * 智能体进化提供者 SPI — 让第三方可整体替换进化系统实现
 * (失败记录 / 用户反应 / 省察引导 / 处置命令)。
 *
 * 默认实现 = 内核 [EvolutionEngine] (行为零变化)。内置插件 plugin-evolution 在
 * onInstall 时注册默认实现; 第三方插件可实现本接口并在 onInstall 时注册自己的
 * 实现 — 后注册者胜。卸载后回退内核默认。
 *
 * 注册表: [EvolutionProviderRegistry] (内核持有, 插件零框架耦合 —
 * 与 DreamProvider / FrameworkAdapter 同模式)。
 */
interface EvolutionProvider {

    /** 提供者名 (调试/日志标识)。 */
    val providerName: String

    /**
     * 处理 evolution.* 命令 (audit/report/learn.command/reactions/mark-corrected)。
     * 返回 null 表示未处理 — 回退内核默认实现。
     */
    suspend fun executeCommand(command: String, args: List<String>, ctx: ExecutionContext): ExecutionResult?

    /** 失败事件 → 失败模式库 (钩子调用, 永不抛异常)。 */
    fun recordFailure(agentName: String?, command: String, errorCode: String, message: String, source: String)

    /** 用户纠正/撤回 → 用户反应档案 (shell 层纠正识别调用, 永不抛异常)。 */
    fun recordCorrection(agentName: String?, correction: String, contextSnippet: String, task: String)

    /** 失败后省察引导 (轻/深分级)。返回 null 表示无需注入。 */
    fun buildFragment(agentName: String?, command: String, message: String): String?

    /** 会话开始绩效提醒 (有未修正复现模式时)。返回 null 表示无需注入。 */
    fun buildSessionBrief(agentName: String?): String?
}

/**
 * 进化提供者注册表 — 内核默认实现兜底; 插件 (plugin-evolution / 第三方) 注册覆盖。
 */
object EvolutionProviderRegistry {
    @Volatile
    private var registered: EvolutionProvider? = null

    /** 注册进化提供者 (后注册者胜 — 第三方插件可覆盖内置默认)。 */
    @Synchronized
    fun register(provider: EvolutionProvider) { registered = provider }

    @Synchronized
    fun unregister(providerName: String) {
        if (registered?.providerName == providerName) registered = null
    }

    /** 当前生效的进化提供者 (无注册 → 内核默认 EvolutionEngine)。 */
    fun active(): EvolutionProvider = registered ?: EvolutionEngine
}

/**
 * 进化系统默认实现 — 处置命令 + 失败记录 + 省察引导 (行为零变化)。
 *
 * 省察引导只负责"提问与处置指引", 处置动作本身由 Agent 通过既有通道执行
 * (agent.memory.keep / agent.write / self.search)。这里的命令是处置侧的动作端点:
 * - audit        绩效报告(失败分布/复现率/教训列表)
 * - report       框架反馈 — Agent 发现框架缺陷时写给开发者
 * - learn.command 指令集丰富 — 把正确用法/关键词登记进命令搜索索引
 * - reactions    查看用户反应档案(用户分身数据源)
 * - mark-corrected 标记失败模式已沉淀修正(绩效闭环)
 */
object EvolutionEngine : EvolutionProvider {

    override val providerName: String = "EvolutionEngine"

    // ── 命令执行 ────────────────────────────────────────────────────

    override suspend fun executeCommand(command: String, args: List<String>, ctx: ExecutionContext): ExecutionResult? =
        when (command) {
            "audit" -> audit(args, ctx)
            "report" -> report(args, ctx)
            "learn.command" -> learnCommand(args, ctx)
            "reactions" -> reactions(args, ctx)
            "mark-corrected" -> markCorrected(args, ctx)
            else -> null
        }

    private fun agent(ctx: ExecutionContext): String = ctx.agentName ?: EvolutionStore.DEFAULT_AGENT

    /** 绩效报告: 失败分布 / 复现率 / 教训列表。Usage: evolution.audit */
    private suspend fun audit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(EvolutionStore.stats(agent(ctx)))
    }

    /**
     * 框架反馈 — Agent 省察发现框架缺陷时写给开发者。
     * 落盘 `{AGENTS}/{agent}/evolution/feedback/YYYYMMDD_HHmmss.md` + 推送提醒用户。
     * Usage: evolution.report <描述> [--context <复现路径>]
     */
    private suspend fun report(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: evolution.report <描述>\n例: evolution.report fs.cat 在中文路径下报错, 怀疑是编码 bug",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val contextIdx = args.indexOf("--context")
        val text = (if (contextIdx >= 0) args.take(contextIdx) else args).joinToString(" ")
        val context = if (contextIdx >= 0 && contextIdx + 1 < args.size) args[contextIdx + 1] else ""
        return try {
            val dir = File(DataPaths.evolutionFeedbackDir(agent(ctx)))
            dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = File(dir, "$ts.md")
            file.writeText(buildString {
                appendLine("# 框架反馈 (Agent 进化)")
                appendLine()
                appendLine("- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}")
                appendLine("- Agent: ${agent(ctx)}")
                appendLine("- 会话: ${ctx.sessionId}")
                appendLine()
                appendLine("## 问题描述")
                appendLine(text)
                if (context.isNotBlank()) {
                    appendLine()
                    appendLine("## 复现路径/上下文")
                    appendLine(context)
                }
            })
            NotifyBus.message("📮 Agent 提交了框架反馈, 请查看: ${file.absolutePath}")
            ExecutionResult.ok("框架反馈已落盘: ${file.absolutePath}\n" +
                "可配合 error.export 导出错误明细 (errors.jsonl 在 ${DataPaths.ERROR_LOG})。")
        } catch (e: Exception) {
            ExecutionResult.fail("写入失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    /**
     * 指令集丰富 — 把正确用法/同义词登记进命令搜索索引。
     * Usage: evolution.learn.command <full-command> <描述> [--keywords 词1,词2]
     */
    private suspend fun learnCommand(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: evolution.learn.command <command> <描述> [--keywords 同义词,逗号分隔]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val kwIdx = args.indexOf("--keywords")
        val head = if (kwIdx >= 0) args.take(kwIdx) else args
        val name = head[0]
        val desc = head.drop(1).joinToString(" ")
        val keywords = if (kwIdx >= 0 && kwIdx + 1 < args.size)
            args[kwIdx + 1].split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
        return try {
            CommandSearch.registerOrUpdate(CommandIndex(
                fullName = name,
                namespace = name.substringBefore("."),
                description = desc,
                usage = name,
                zhKeywords = keywords.ifEmpty { listOf(name, name.substringAfterLast(".")) },
                enKeywords = keywords.ifEmpty { listOf(name) }
            ))
            val kwText = if (keywords.isEmpty()) "(自动)" else keywords.joinToString(", ")
            ExecutionResult.ok("已丰富指令集: $name\n描述: $desc\n关键词: $kwText")
        } catch (e: Exception) {
            ExecutionResult.fail("索引更新失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** 查看用户反应档案(用户分身数据源)。Usage: evolution.reactions */
    private suspend fun reactions(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(EvolutionStore.reactionsText(agent(ctx)))
    }

    /** 标记失败模式已沉淀修正(绩效闭环)。Usage: evolution.mark-corrected <failure-id> */
    private suspend fun markCorrected(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val id = args.firstOrNull()
            ?: return ExecutionResult.fail("Usage: evolution.mark-corrected <failure-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return if (EvolutionStore.markCorrected(agent(ctx), id)) {
            ExecutionResult.ok("已标记修正: $id (问题不复现 🎯)")
        } else {
            ExecutionResult.fail("未找到失败记录: $id (用 evolution.audit 查看)", errorCode = ErrorCodes.ERR_NOT_FOUND)
        }
    }

    // ── 失败记录 (钩子委托) ─────────────────────────────────────────

    override fun recordFailure(
        agentName: String?,
        command: String,
        errorCode: String,
        message: String,
        source: String
    ) {
        EvolutionStore.recordFailure(agentName, command, errorCode, message, source)
    }

    override fun recordCorrection(agentName: String?, correction: String, contextSnippet: String, task: String) {
        EvolutionStore.recordCorrection(agentName, correction, contextSnippet, task)
    }

    // ── 省察引导 ────────────────────────────────────────────────────

    /**
     * 失败后生成引导片段。基于 [EvolutionStore] 最新记录分级:
     * - 首次失败(轻): 一句提示 + CommandSearch 检索的正确用法。
     * - 重复失败(同模式 ≥2 次): 完整四层引导 + 四分法处置映射。
     * 返回 null 表示无需注入。
     */
    override fun buildFragment(agentName: String?, command: String, message: String): String? {
        val failure = EvolutionStore.recentFailures(agentName, 1).firstOrNull() ?: return null
        return if (failure.repeatCount >= 2) deepGuide(failure, agentName)
               else lightGuide(failure)
    }

    /** 会话开始时的绩效提醒 — 有未修正的复现模式时注入一次 (无复现时零开销)。 */
    override fun buildSessionBrief(agentName: String?): String? {
        val repeated = EvolutionStore.repeatedPatterns(agentName, 3)
        if (repeated.isEmpty()) return null
        return buildString {
            appendLine("【进化 · 会话提醒】你有复现失败模式, 先避免再犯:")
            repeated.forEach { f ->
                appendLine("- ${f.command} [${f.errorCode}] ×${f.repeatCount}${if (f.corrected) " ✅已修正" else " ⚠️未修正"}")
            }
            appendLine("未修正的: 先 self.search 检索正确做法, 再 agent.memory.keep 沉淀教训; 属框架缺陷用 evolution.report 反馈。")
        }.trimIndent()
    }

    // ── 轻失败: 一句提示 + 正确用法 ────────────────────────────────

    private fun lightGuide(failure: EvolutionFailure): String {
        return buildString {
            appendLine("【进化 · 轻】你刚才的命令执行失败了, 快速自查后继续:")
            appendLine("- 失败命令: ${failure.command} [${failure.errorCode}]")
            appendLine("- 原因: ${failure.message}")
            val usage = searchUsage(failure.command)
            if (usage.isNotBlank()) {
                appendLine(usage)
                appendLine("- 若检索结果里有正确命令/用法, 用它重试; 若已确认是正确做法, 忽略本次提示继续。")
            } else {
                appendLine("- 用 self.search <自然语言描述> 检索正确命令, 或调整参数重试。")
            }
        }.trimIndent()
    }

    // ── 重/重复失败: 金字塔四层深省察 ─────────────────────────────

    private fun deepGuide(failure: EvolutionFailure, agentName: String?): String {
        val reactions = EvolutionStore.reactionsText(agentName)
        return buildString {
            appendLine("【进化 · 深】同样的失败已是第 ${failure.repeatCount} 次 — 必须找出根因, 问题不复现。")
            appendLine("按金字塔四层向自己提问:")
            appendLine()
            appendLine("L1 事实: 我执行了什么?结果是什么?")
            appendLine("    ${failure.command}: ${failure.message}")
            appendLine()
            appendLine("L2 归因: 我用了什么方法?为什么没成功?是命令用法错、方法错, 还是目标本身不对?")
            appendLine()
            appendLine("L3 用户视角: 用户看到我这样失败会怎么评价?这影响用户的什么?")
            appendLine("    (用户对我的历史纠正:)")
            appendLine("    ${reactions.take(500)}")
            appendLine()
            appendLine("L4 进化: 正确的做法是什么?如何确保不复现?")
            appendLine("    按错误类型处置:")
            appendLine("    - 指令集错误(命令/参数用错): evolution.learn.command 丰富指令集, 或 self.search 检索正确命令")
            appendLine("    - 常识性错误: agent.memory.keep <教训> 写入长期记忆 (下次自动注入)")
            appendLine("    - 行为错误(风格/边界/习惯): agent.write 调整 soul.md 行为准则")
            appendLine("    - 框架缺陷(命令本身坏了): evolution.report <描述> 写给开发者")
            appendLine()
            appendLine("沉淀完成后用 evolution.mark-corrected ${failure.id} 标记已修正。")
            val usage = searchUsage(failure.command)
            if (usage.isNotBlank()) appendLine(usage)
        }.trimIndent()
    }

    /** 用命令名检索相关命令 (BM25), 返回紧凑用法文本; 无结果返回空串。 */
    private fun searchUsage(query: String): String {
        return try {
            val results = CommandSearch.search(query, 3)
            if (results.isEmpty()) ""
            else "相关命令检索:\n" + CommandSearch.formatResults(results, query)
        } catch (_: Exception) {
            ""
        }
    }
}
