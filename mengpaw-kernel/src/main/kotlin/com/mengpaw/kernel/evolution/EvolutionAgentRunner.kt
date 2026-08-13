// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.llm.LlmProvider

/**
 * Evolution Agent — 独立进化分析子 Agent (v0.37.3)。
 *
 * 定位: 收集 (failures.jsonl) 与沉淀 (主 Agent 采纳) 之间的分析层。
 * 读取未分析失败批次 (有限上下文), 用独立"进化分析师"提示词分析
 * (金字塔追问 + 低前缀增量), 产出 md 报告落盘 reports/, 由主 Agent
 * 按 evolution 技能审阅采纳。
 *
 * 触发: 新失败累计 ≥ [EvolutionStore.ANALYSIS_BATCH] 条 (shouldTrigger),
 * 会话结束兜底; 报告按最后变更时间保留 [EvolutionStore.REPORT_TTL_DAYS] 天后清理。
 */
class EvolutionAgentRunner(private val llmProvider: LlmProvider) {

    /** 待分析失败数达批次阈值即应触发。 */
    fun shouldTrigger(agentName: String?): Boolean =
        EvolutionStore.pendingFailureCount(agentName) >= EvolutionStore.ANALYSIS_BATCH

    /**
     * 执行一次分析: 读未分析批次 → LLM 分析 → 报告落盘 → 更新进度。
     * @return 报告绝对路径; 无待分析数据或分析失败返回 null。
     */
    suspend fun runAnalysis(agentName: String?): String? {
        val pending = EvolutionStore.pendingFailures(agentName, EvolutionStore.ANALYSIS_BATCH)
        if (pending.isEmpty()) return null
        val reportMd = try {
            llmProvider.complete(buildPrompt(agentName, pending))
        } catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w(
                "EvolutionAgent",
                "分析失败 (${pending.size} 条待分析): ${e.message?.take(120) ?: "未知错误"}"
            )
            return null
        }
        val path = EvolutionStore.saveReport(agentName, reportMd, pending.size)
        EvolutionStore.markAnalyzed(agentName, pending.size)
        return path
    }

    /** 进化分析师提示词 — 金字塔追问 + 低前缀增量, 只注入有限失败上下文。 */
    internal fun buildPrompt(agentName: String?, failures: List<EvolutionFailure>): String = buildString {
        appendLine("你是进化分析师。基于以下失败记录, 按金字塔追问法输出一份增量式进化报告。")
        appendLine()
        appendLine("分析原则:")
        appendLine("- 结论先行: 最该防的 1-3 个失败模式")
        appendLine("- 每个模式用 5-Why 挖到可执行根因 (命令/文件/行为层面), 不接受粗心/忘记类空泛归因")
        appendLine("- 证据必须来自失败记录 (命令/错误码/复现数), 禁止虚构或扩大化")
        appendLine("- 输出增量教训: 每条教训只写一次, 给出可执行建议动作")
        appendLine("- 报告用 markdown")
        appendLine()
        appendLine("失败记录 (${failures.size} 条):")
        failures.forEachIndexed { i, f ->
            appendLine("[$i] 命令: ${f.command} | 错误: ${f.errorCode} | 复现: ×${f.repeatCount} | 已修正: ${f.corrected}")
            if (f.task.isNotBlank()) appendLine("    任务: ${f.task.take(120)}")
            if (f.contextSnippet.isNotBlank()) appendLine("    上下文: ${f.contextSnippet.replace("\n", " ").take(200)}")
        }
        appendLine()
        appendLine("报告结构:")
        appendLine("## 结论 (最该防的模式)")
        appendLine("## 根因分析 (每模式 5-Why)")
        appendLine("## 可执行教训与建议动作")
    }
}
