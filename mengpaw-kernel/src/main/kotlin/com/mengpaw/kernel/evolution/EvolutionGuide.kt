// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.cli.CommandSearch

/**
 * 进化省察引导 — 让 Agent 在失败后按金字塔四层向自己提问。
 *
 * 不新建第二个 LLM:引导以 system 消息注入主对话, Agent 自己完成
 * L1 事实 → L2 归因 → L3 用户视角(检索用户反应档案) → L4 进化(错误四分法处置)。
 *
 * 分级:
 * - [buildFragment] 轻失败(首次): 一句提示 + CommandSearch 检索的正确用法。
 * - [buildFragment] 重/重复失败(同模式 ≥2 次): 完整四层引导 + 四分法处置映射。
 * - [buildSessionBrief] 会话开始: 有未修正复现模式时注入绩效提醒。
 */
object EvolutionGuide {

    /** 每会话引导注入上限 — 防刷屏, 保上下文。 */
    const val MAX_INJECTIONS = 3

    /**
     * 失败后生成引导片段。基于 [EvolutionStore] 最新记录分级。
     * 返回 null 表示无需注入。
     */
    fun buildFragment(agentName: String?, command: String, message: String): String? {
        val failure = EvolutionStore.recentFailures(agentName, 1).firstOrNull() ?: return null
        return if (failure.repeatCount >= 2) deepGuide(failure, agentName)
               else lightGuide(failure)
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

    // ── 绩效反馈: 会话开始注入未消化教训 ──────────────────────────

    /**
     * 会话开始时的绩效提醒 — 有未修正的复现模式时注入一次。
     * 无复现模式时返回 null(零开销)。
     */
    fun buildSessionBrief(agentName: String?): String? {
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

    // ── 内部 ────────────────────────────────────────────────────────

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
