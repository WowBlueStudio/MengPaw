// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.GoalSession
import com.mengpaw.kernel.agent.GoalSessionStore
import com.mengpaw.kernel.agent.RubricEvaluator

/**
 * Goal-mode execution with RubricGate auto-completion detection.
 *
 * Each turn: inject goal prompt -> run ReAct loop -> evaluate completion via LLM.
 * Stops when RubricGate returns SATISFIED or max iterations exhausted.
 *
 * Ported from QwenPaw GoalMode architecture.
 */
class GoalModeExecutor(
    private val agentEngine: AgentEngine
) {
    /**
     * Run a goal-oriented task with automatic completion detection (RubricGate).
     *
     * @param task the goal description
     * @param maxTurns maximum goal iterations
     * @param maxTokensBudget maximum tokens to spend across all turns
     * @param sessionFile optional 持久化文件 (P2-4): 非 null 时每轮结束后落盘会话状态;
     *   若该文件已存在 (上次中断残留) 则从存档续跑而非新建, 实现跨会话恢复。
     * @param onStep optional step callback for progress tracking
     * @return the final result string
     */
    suspend fun runWithGoal(
        task: String, maxTurns: Int = 20, maxTokensBudget: Int = 300_000,
        sessionFile: String? = null,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null
    ): String {
        val llmProvider = agentEngine.getLlmProvider()
        // P0 注入防护: 任务入口静默剥离精确注入模式
        val guardedTask = com.mengpaw.kernel.security.UntrustedContent.sanitizeForAgent(task)
        // P2-4: 续跑 — 若指定了存档文件且存在有效会话 (目标一致), 复用其状态继续;
        // 否则新建会话。
        val persistFile = sessionFile?.let { java.io.File(it) }
        val resumeSession = persistFile?.let { GoalSessionStore.load(it) }
        val session = resumeSession
            ?.takeIf { it.goal == guardedTask }
            ?: GoalSession(
                goal = guardedTask, maxIterations = maxTurns, maxTokens = maxTokensBudget
            )
        val evaluator = RubricEvaluator()
        val turnResults = mutableListOf<String>()
        var offTrackCount = 0

        // 续跑: 从上次已完成的轮次继续, 不重做已完成轮次
        for (turn in session.iteration until session.maxIterations) {
            if (!session.active) break
            session.iteration = turn + 1

            // Build goal-aware prompt — RubricGate feedback is the only signal needed between turns.
            // Prior turn results are NOT replayed; replaying biases the agent toward repeating old work.
            val goalPrompt = if (turn == 0) {
                "## 目标\n${session.goal}\n\n使用 Thought → Action → Final Answer 格式。" +
                    "自然对话，不要主动汇报进度或回溯历史——除非用户询问。" +
                    "如果任务无法完成（如缺少必要权限/信息/资源），请在 Final Answer 中明确说明无法完成并停止，不要空转。" +
                    "【目标一致性】你的每一步行动必须服务于原目标，禁止擅自扩展或偏离任务范围；" +
                    "若某操作与目标无关，立即停止并说明原因。"
            } else {
                "## 目标 (第 ${turn + 1}/${session.maxIterations} 轮)\n${session.goal}\n\n反馈: ${session.lastFeedback.ifEmpty { "无" }}" +
                    "如果任务无法完成，明确说明并停止，不要空转。" +
                    "【目标一致性】不得偏离原目标；回到目标继续。"
            }

            // Run ReAct loop — no prior context injection; RubricGate feedback is sufficient
            val result = agentEngine.runReActLoop(
                task = "$goalPrompt\n\n$guardedTask",
                maxSteps = 50,
                onStep = onStep,
                onDelta = onDelta,
                onReasoning = onReasoning
            )
            turnResults.add(result)

            // v0.37.3: LLM 明确表达任务不可完成 → 提前中断, 不空转到 maxTurns 耗尽
            val interruptReason = detectImpossible(result)
            if (interruptReason != null) {
                session.active = false
                session.lastVerdict = "INTERRUPTED"
                session.lastFeedback = interruptReason
                break
            }

            // Budget gate: estimate tokens from result length
            session.tokensUsed += result.length / 4  // rough char->token estimate
            if (session.tokensUsed >= maxTokensBudget) {
                session.active = false
                session.lastVerdict = "Token budget exceeded"
                break
            }

            // RubricGate: LLM-based completion evaluation on every turn
            val evalPrompt = evaluator.buildPrompt(session.goal, result)
            try {
                val evalResult = llmProvider.complete(evalPrompt)
                when (classifyEval(evalResult)) {
                    GoalEval.SATISFIED -> {
                        session.lastVerdict = "SATISFIED"
                        session.active = false
                    }
                    GoalEval.OFFTRACK -> {
                        // v0.37.3 目标一致性: 偏离目标时反馈纠正, 连续 2 轮仍偏离 → 中断
                        offTrackCount++
                        session.lastVerdict = "OFFTRACK"
                        session.lastFeedback = "⚠️ 你偏离了原目标，请回到目标并继续: ${session.goal}"
                        if (offTrackCount >= 2) {
                            session.active = false
                            session.lastVerdict = "OFFTRACK_ABORT"
                            break
                        }
                    }
                    GoalEval.NEEDS_REVISION -> {
                        session.lastVerdict = "NEEDS_REVISION"
                        session.lastFeedback = evalResult.take(200)
                    }
                }
            } catch (_: Exception) {
                // LLM eval failed — fall back to heuristic
                if (result.contains("Final Answer:", ignoreCase = true)) {
                    session.lastVerdict = "SATISFIED (heuristic)"
                    session.active = false
                }
            }
            // P2-4: 每轮结束落盘会话状态 — 中断后可从存档续跑
            persistFile?.let { GoalSessionStore.save(session, it) }
        }

        // P2-4: 走到这里即本次运行已终结 (完成/中断/预算耗尽/轮次耗尽) — 清理存档,
        // 避免下次误续跑一个已无预算的旧会话。若中途被取消 (CancellationException) 抛出,
        // 则不会执行到此处, 存档保留供续跑。
        persistFile?.let { GoalSessionStore.clear(it) }

        return if (!session.active && session.lastVerdict.startsWith("SATISFIED")) {
            "目标已完成: ${session.goal}\n\n" + turnResults.lastOrNull().orEmpty()
        } else if (!session.active && session.lastVerdict == "INTERRUPTED") {
            "任务中断: LLM 判断无法完成 — ${session.goal}\n\n最后结果:\n" +
                turnResults.lastOrNull().orEmpty()
        } else if (!session.active && session.lastVerdict == "OFFTRACK_ABORT") {
            "任务中断: 连续偏离原目标 — ${session.goal}\n\n最后结果:\n" +
                turnResults.lastOrNull().orEmpty()
        } else {
            "目标未完成 (${session.iteration}/${maxTurns} 轮): ${session.goal}\n\n最后结果:\n" +
                turnResults.lastOrNull().orEmpty()
        }
    }

    /**
     * 不可完成信号检测 — LLM 明确表达任务无法完成/请求中断时返回原因, 否则 null。
     * internal 为测试可见性; 保守匹配 (避免把"无法完成某步骤但整体继续"误判为中断)。
     */
    internal fun detectImpossible(text: String): String? {
        val lower = text.lowercase()
        val patterns = listOf(
            "无法完成任务", "无法完成该任务", "任务无法完成", "此任务无法完成", "任务不可完成",
            "无法继续执行该任务", "无法执行该任务", "请求中断",
            "i cannot complete", "cannot complete this task", "task is impossible",
            "impossible to complete", "abort the task", "cannot be completed"
        )
        return patterns.firstOrNull { lower.contains(it) }
    }

    /** 评估结果三态分类 (v0.37.3) — YES=完成 / OFFTRACK=偏离目标 / 其他=继续。 */
    internal enum class GoalEval { SATISFIED, OFFTRACK, NEEDS_REVISION }

    internal fun classifyEval(result: String): GoalEval {
        val upper = result.trim().uppercase()
        return when {
            upper.startsWith("YES") -> GoalEval.SATISFIED
            upper.startsWith("OFFTRACK") || upper.startsWith("OFF-TRACK") ||
                result.contains("偏离", ignoreCase = true) -> GoalEval.OFFTRACK
            else -> GoalEval.NEEDS_REVISION
        }
    }
}
