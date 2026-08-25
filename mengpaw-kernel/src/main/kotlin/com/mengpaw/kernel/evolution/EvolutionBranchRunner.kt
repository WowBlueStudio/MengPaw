// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelDispatchers
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.LinuxCommandExecutor
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.security.HighRiskCommandGate
import com.mengpaw.kernel.security.InjectionPatterns
import com.mengpaw.kernel.security.RiskGate
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.security.SourceBlocklist
import com.mengpaw.kernel.security.UntrustedContent
import com.mengpaw.kernel.session.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 静默分支进化执行器 (v0.44) — 把进化队列项在独立分支会话中自动处理。
 *
 * - 每个分支会话 scope="evolution", activate=false (不抢占 activeSessionId),
 *   复用零待命纪律 (不写 _state/conversationSessionId, 不污染主会话)。
 * - 会话**保留** (不销毁) — 关键对话进右侧边栏, 供用户复盘检验 (区别于 swarm 零待命销毁)。
 * - 分支 Agent 读失败/纠正数据 → 按 evolution-branch 剧本沉淀到共享工作区
 *   (memory/commands/soul/skills) → markCorrected; 完成后移除已处理队列项。
 * - 高危命令 worker 式直拒 (allowUserConfirm=false) — 静默分支不弹窗。
 */
internal class EvolutionBranchRunner(private val engine: AgentEngine) {

    private companion object {
        /** 单批最多处理的队列项数 (防单次分支超上下文)。 */
        const val MAX_ITEMS_PER_RUN = 5
        /** 单分支最大 ReAct 步数。 */
        const val MAX_STEPS = 15
        /** skill 剧本缺失时的精简内置引导 (正常由 SkillSeeds 同步 evolution-branch.md)。 */
        val FALLBACK_GUIDANCE = buildString {
            appendLine("你是进化分支 Agent, 静默运行, 不向用户发消息。只从失败/纠正中沉淀可复用教训到共享工作区。")
            appendLine("分析: 金字塔四层 (L1 事实/L2 归因/L3 用户视角/L4 进化) + 5-Why 挖到可执行根因; 禁止粗心/忘记类不可执行归因; 先聚类再处置。")
            appendLine("重点两类 (不处理幻觉): 语义判断失误 / 工具调用路径问题。")
            appendLine("沉淀: 基础进化优先 (已有 skill/记忆/指令集则在基础上增改合并, 不新建重复 skill); 每条按 触发条件/正确做法/反例/验证方法 四要素落盘。")
            appendLine("动作: 命令用法错→evolution.learn.command; 语义错→agent.memory.keep; 行为边界→编辑 soul.md; 可复用套路→make_skills; 命令坏了→evolution.report。")
            appendLine("闭环: 每条沉淀成功后才 evolution.mark-corrected <id>; 幂等 (已沉淀跳过)。")
            appendLine("纪律: 只写共享工作区; 不改主会话历史; 不做用户任务。")
        }
    }

    /**
     * 处理某 agent 待处理队列 (最多 [MAX_ITEMS_PER_RUN] 项)。
     * @return 成功处理并移除的队列项数。
     */
    suspend fun drain(agentName: String?, provider: LlmProvider): Int {
        val items = EvolutionQueue.pendingItems(agentName).take(MAX_ITEMS_PER_RUN)
        if (items.isEmpty()) return 0
        val ok = runBranch(agentName, items, provider)
        if (ok) EvolutionQueue.removeProcessed(agentName, items)
        return if (ok) items.size else 0
    }

    private suspend fun runBranch(
        agentName: String?,
        items: List<EvolutionQueueItem>,
        provider: LlmProvider
    ): Boolean {
        val agent = agentName?.replace(Regex("[/\\\\]"), "_") ?: EvolutionStore.DEFAULT_AGENT
        val session = engine.getSessionManager().createSession(
            task = "进化分支 · 静默沉淀",
            metadata = mapOf("evolutionId" to "evo-${System.currentTimeMillis()}"),
            scope = "evolution",
            agentId = engine.agentName,
            activate = false
        )
        try {
            val context = ExecutionContext(
                sessionId = session.id, agentName = engine.agentName, scope = "evolution"
            )
            val guidance = skillGuidance()
            engine.getSessionManager().addMessage(session.id, Message("system", guidance))
            engine.getSessionManager().addMessage(session.id, Message("user", buildInput(agent, items)))

            var step = 0
            while (step < MAX_STEPS) {
                val job = currentCoroutineContext()[Job]
                if (job != null && !job.isActive) throw CancellationException("Evolution branch stopped")
                val conversation = engine.buildConversation(session.id)
                val response = try { provider.completeWithMessages(conversation) }
                catch (e: Exception) {
                    KernelLog.w("EvolutionBranch", "LLM 调用失败: ${e.message?.take(120)}")
                    return false
                }
                val sanitized = Sanitizer.sanitize(response)
                engine.getSessionManager().addMessage(session.id, Message("assistant", sanitized))

                val parsed = engine.getPromptEngine().parse(sanitized)
                if (parsed.isFinal) {
                    // 关键对话落盘到会话 (右侧边栏可复盘), 结束
                    appendSummary(session.id, items)
                    return true
                }
                if (parsed.needsContinue) {
                    engine.getSessionManager().addMessage(session.id,
                        Message("user", "继续。输出 Action: <命令> 和 Action Input: <参数>。"))
                    continue
                }
                val actionList = parsed.actions.ifEmpty { listOfNotNull(parsed.action) }
                if (actionList.isNotEmpty()) {
                    val observations = coroutineScope {
                        actionList.map { call ->
                            async(KernelDispatchers.BACKGROUND) {
                                val gate = HighRiskCommandGate.evaluate(call)
                                val commandLine = gate.commandLine
                                val result = try {
                                    when {
                                        gate.error != null ->
                                            ExecutionResult.fail(gate.error, errorCode = gate.errorCode ?: ErrorCodes.PARAM_FORMAT_ERROR)
                                        else -> {
                                            val riskError = RiskGate.evaluate(gate, engine.agentName, allowUserConfirm = false)
                                            if (riskError != null) {
                                                ExecutionResult.fail(riskError, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
                                            } else {
                                                val source = SourceBlocklist.extractSource(commandLine)
                                                if (source != null && SourceBlocklist.isBlocked(source)) {
                                                    ExecutionResult.fail("来源已在黑名单，工具结果已阻止。", errorCode = ErrorCodes.ERR_SOURCE_BLOCKED)
                                                } else {
                                                    val pipelineResult = withTimeout(60_000L) {
                                                        engine.getPipelineManager().buildPipeline().execute(commandLine, context)
                                                    }
                                                    if (!pipelineResult.success &&
                                                        pipelineResult.errorCode == ErrorCodes.ERR_NOT_FOUND &&
                                                        pipelineResult.error?.startsWith("Unknown command") == true
                                                    ) {
                                                        LinuxCommandExecutor.execute(commandLine, context, allowUserConfirm = false)
                                                    } else {
                                                        pipelineResult
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    ExecutionResult.fail("命令超时 (60s): $commandLine", errorCode = ErrorCodes.ERR_INTERNAL)
                                }
                                val observation = (if (result.success) result.output
                                    else (result.errorCode?.let { "Error [$it]: ${result.error}" } ?: "Error: ${result.error}"))
                                    .take(2000)
                                val cleaned = UntrustedContent.stripInjection(observation)
                                InjectionPatterns.findMatch(observation)?.let {
                                    KernelLog.w("EvolutionBranch", "检测到疑似$it, 内容已净化")
                                }
                                "Command: $commandLine\nResult: ${UntrustedContent.wrap(cleaned)}"
                            }
                        }.awaitAll()
                    }
                    engine.getSessionManager().addMessage(session.id, Message("assistant", observations.joinToString("\n\n")))
                }
                step++
            }
            appendSummary(session.id, items)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KernelLog.w("EvolutionBranch", "分支进化失败: ${e.message?.take(120)}")
            return false
        } finally {
            // 会话保留 (右侧边栏可复盘) — 不销毁
        }
    }

    /** 读取 evolution-branch 技能剧本 (DataPaths.SKILLS 已由 SkillSeeds 同步); 缺省回退内置精简引导。 */
    private fun skillGuidance(): String {
        return try {
            val f = File(DataPaths.SKILLS, "evolution-branch.md")
            if (f.exists()) f.readText()
            else FALLBACK_GUIDANCE
        } catch (_: Exception) { FALLBACK_GUIDANCE }
    }

    /** 组装分支输入: 队列项 + 失败模式详情 (从 failures.jsonl 取复现数/上下文)。 */
    private fun buildInput(agent: String, items: List<EvolutionQueueItem>): String = buildString {
        appendLine("以下是待进化沉淀的素材。请按上述剧本分析并沉淀, 完成后总结你做了什么。")
        appendLine()
        appendLine("【待处理素材 (${items.size} 条)】")
        items.forEachIndexed { i, it ->
            appendLine("[$i] 类型: ${if (it.isFailure) "失败" else "用户纠正"} | 时间: ${it.timestamp}")
            if (it.isFailure) {
                appendLine("    命令: ${it.command} [${it.errorCode}]")
                if (it.message.isNotBlank()) appendLine("    信息: ${it.message.take(200)}")
                if (it.task.isNotBlank()) appendLine("    任务: ${it.task.take(120)}")
                if (it.contextSnippet.isNotBlank()) appendLine("    上下文: ${it.contextSnippet.replace("\n", " ").take(200)}")
                // 补复现数 (来自失败模式库)
                val f = EvolutionStore.recentFailures(agent, 20).firstOrNull { fr ->
                    fr.command == it.command && fr.errorCode == it.errorCode
                }
                if (f != null) appendLine("    复现: ×${f.repeatCount}${if (f.corrected) " 已修正" else " 未修正"} (id=${f.id})")
            } else {
                appendLine("    纠正: ${it.correction}")
                if (it.contextSnippet.isNotBlank()) appendLine("    上文: ${it.contextSnippet.replace("\n", " ").take(200)}")
                if (it.task.isNotBlank()) appendLine("    任务: ${it.task.take(120)}")
            }
        }
        appendLine()
        appendLine("要求: 每类问题按四要素 (触发条件/正确做法/反例/验证方法) 沉淀; 已有 skill/记忆/指令集则在其基础上进化, 不新建重复 skill; 沉淀成功后 evolution.mark-corrected 标记闭环 (未沉淀的失败不得标记)。")
    }

    /** 把本次进化"关键对话"摘要写入会话 (供右侧边栏复盘), 不展开早期工具噪音。 */
    private fun appendSummary(sessionId: String, items: List<EvolutionQueueItem>) {
        try {
            val summary = buildString {
                appendLine("🧬 进化分支完成 — 处理 ${items.size} 条素材, 沉淀到共享工作区 (记忆/指令集/灵魂/技能)。")
                appendLine("素材: ${items.joinToString("; ") { if (it.isFailure) "${it.command} [${it.errorCode}]" else "纠正: ${it.correction.take(40)}" }}")
                appendLine("可 evolution.audit 查看沉淀状态; 已 mark-corrected 的失败模式不再提示。")
            }
            engine.getSessionManager().addMessage(sessionId, Message("assistant", summary))
        } catch (_: Exception) {}
    }
}
