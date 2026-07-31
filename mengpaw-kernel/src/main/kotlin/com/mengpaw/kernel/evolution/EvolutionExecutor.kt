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
 * 进化系统命令 — evolution.* 命名空间(内核内置)。
 *
 * 省察引导只负责"提问与处置指引", 处置动作本身由 Agent 通过既有通道执行
 * (agent.memory.keep / agent.write / self.search)。这里的命令是处置侧的动作端点:
 * - audit        绩效报告(失败分布/复现率/教训列表)
 * - report       框架反馈 — Agent 发现框架缺陷时写给开发者
 * - learn.command 指令集丰富 — 把正确用法/关键词登记进命令搜索索引
 * - reactions    查看用户反应档案(用户分身数据源)
 * - mark-corrected 标记失败模式已沉淀修正(绩效闭环)
 */
object EvolutionExecutor {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "audit" to ::audit,
        "report" to ::report,
        "learn.command" to ::learnCommand,
        "reactions" to ::reactions,
        "mark-corrected" to ::markCorrected
    )

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
}
