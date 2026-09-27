// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.session.ResumeLock
import com.mengpaw.kernel.session.ResumePlan
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi

// ── 断点续跑恢复协调 (P0-3 / P2-10) — 自 SessionPersistenceService 拆出 (400 行红线) ──
// 判定语义全部在内核 (ResumePlanner + decideRecovery), 本文件只做两件事:
//   ① 把内核判定结果折算成 shell 动作 (自动续跑 / 提示用户 / 无动作);
//   ② 承担"恢复期判定、provider 就绪后执行"的两阶段时序 (见 [ResumeRequest] KDoc)。

/** 续跑判定读取的会话事件条数 (覆盖内核"连续错误 ≥5"判据)。 */
private const val RESUME_EVENTS_LIMIT = 50

/**
 * 待执行的续跑请求 (由 [SessionPersistenceService] 交给 shell 侧执行器)。
 *
 * 两阶段执行的理由 (冷启动时序): [SessionPersistenceService.restoreCurrentSession] 在
 * AgentViewModel 构造期就要把 UI 消息恢复出来, 但那时 LLM provider 尚未注入
 * (AppRoot 的 applyConfiguration 在其后) — 此时续跑只会拿到未配置的 provider 并立刻失败。
 * 故恢复阶段只做"判定 + 记账", 真正触发延后到 provider 就绪 ([AgentViewModel.applyConfiguration])。
 *
 * @param sessionId 续跑会话 id (检查点原 id)
 * @param step 已消耗步数
 * @param task 续跑任务文本 (检查点 messages 末条 user, 内核解析)
 * @param pendingIntents 未确认完成的工具名 (非空时引擎侧会注入核对提示)
 */
internal data class ResumeRequest(
    val sessionId: String,
    val step: Int,
    val task: String?,
    val pendingIntents: List<String>
)

/** [planSessionResume] 的三种结果。 */
internal sealed interface ResumeOutcome {
    /** 可自动续跑 — 装载待执行计划。 */
    data class Auto(val plan: ResumePlan.AutoResume) : ResumeOutcome

    /** 需用户介入 — 追加 UI 提示。 */
    data class Prompt(val hint: String) : ResumeOutcome

    /** 无动作 (无检查点 / 已有终态 / 判定失败)。 */
    data object None : ResumeOutcome
}

/**
 * 恢复阶段判定是否自动续跑。
 *
 * 前置: [engineSessionId] 无 RUNNING 检查点且上次确实卡住 ([wasStuck]) → 只给提示不强跑
 * (没有可复原的进度, 强跑等于重发任务)。有 RUNNING 检查点则交给内核 [AgentEngine.planResume]
 * —— 开关 / 单会话锁 / 事件分级恢复 (decideRecovery) 的判定语义全在内核, shell 不重复实现。
 *
 * @param wasStuck 上次运行被进程死亡打断 (UI 消息侧判定)
 * @param pendingIntents 未确认完成的工具名 (仅用于诊断日志, 实际注入由引擎完成)
 */
internal fun planSessionResume(
    engine: AgentEngine,
    engineSessionId: String?,
    wasStuck: Boolean,
    pendingIntents: List<String> = emptyList()
): ResumeOutcome {
    if (engineSessionId.isNullOrBlank()) return ResumeOutcome.None
    return try {
        if (!engine.hasResumableCheckpoint(engineSessionId)) {
            return if (wasStuck) ResumeOutcome.Prompt(resumeHint(0)) else ResumeOutcome.None
        }
        when (val plan = engine.planResume(engineSessionId, ResumeLock())) {
            is ResumePlan.AutoResume -> {
                if (pendingIntents.isNotEmpty()) {
                    KernelLog.w("AgentViewModel", "续跑含未确认完成的工具调用: ${pendingIntents.joinToString(",")}")
                }
                ResumeOutcome.Auto(plan)
            }
            // PromptUser 只带原因 (无步号字段) —— 提示语直接用内核给出的用户可读文案
            is ResumePlan.PromptUser -> ResumeOutcome.Prompt(plan.reason.userMessage)
            is ResumePlan.Skip -> ResumeOutcome.None
            null -> ResumeOutcome.None
        }
    } catch (e: Exception) {
        KernelLog.w("AgentViewModel", "续跑判定失败, 保持手动恢复: ${e.message}")
        ResumeOutcome.None
    }
}

/** 用户可读的续跑提示 (步号仅作参考, 不可续跑时给 0)。 */
internal fun resumeHint(step: Int): String =
    "上次任务在第 $step 步中断，回复「继续」可接着跑"

/**
 * 冷启动会话恢复编排 (自 [SessionPersistenceService.restoreCurrentSession] 拆出) —
 * 读盘 → 中断态归一化 → 引擎会话重建 → 续跑判定 → 侧栏记录落账。
 *
 * 与原内联实现逐行等价, 仅把"服务内部状态"改为经参数读写 (history / currentSessionId /
 * pendingResume), 语义不变。返回 true 表示恢复成功 (调用方据此认为当前会话已就绪)。
 */
internal fun restoreSessionFromDisk(
    sessions: MutableMap<String, AgentSession>,
    activeAgentName: String,
    history: List<SessionPersistenceService.SessionRecord>,
    setHistory: (List<SessionPersistenceService.SessionRecord>) -> Unit,
    persistHistory: (List<SessionPersistenceService.SessionRecord>) -> Unit,
    setCurrentSessionId: (String) -> Unit,
    markPendingResume: (ResumePlan.AutoResume?) -> Unit
): Boolean {
    val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
    val deleteFile = { try { file.delete() } catch (_: Exception) { } }
    return try {
        when (val read = readCurrentSessionFile()) {
            is CurrentSessionRead.Missing -> false
            is CurrentSessionRead.Corrupt -> {
                deleteFile()
                false
            }
            is CurrentSessionRead.Ok -> {
                val msgs = read.msgs
                val lastMsg = msgs.lastOrNull()
                // 上次以"执行出错"收尾 → 保持原语义: 删档不恢复
                if (lastMsg is ChatMessageUi.Agent && lastMsg.content.startsWith("执行出错")) {
                    deleteFile()
                    return false
                }
                val (recovered, wasStuck) = recoverInterruptedMessages(msgs)
                val session = sessions[activeAgentName] ?: return false
                session.messages.value = recovered

                // ── 引擎会话重建 (进程死亡后 SessionManager 内存恒空) ──
                if (msgs.isNotEmpty()) {
                    val engineMsgs = toEngineConversation(msgs)
                    val (restoredId, prevEngineId) = readEngineSessionIds()
                    val engineSessionId = restoredId ?: "sess_${System.currentTimeMillis()}"
                    try {
                        session.engine.restoreConversation(
                            externalSessionId = engineSessionId,
                            messages = engineMsgs,
                            lastWasInterrupted = wasStuck,
                            previousEngineSessionId = prevEngineId
                        )
                    } catch (_: Exception) { /* engine restore best-effort */ }
                }

                // ── 断点续跑 (P0-3): 恢复后判定是否自动接着跑 ──
                // 判定不触发执行 — provider 此时尚未注入, 执行延后到 provider 就绪
                val outcome = planSessionResume(session.engine, readEngineSessionIds().second, wasStuck)
                when (outcome) {
                    is ResumeOutcome.Auto -> {
                        markPendingResume(outcome.plan)
                        KernelLog.i("AgentViewModel",
                            "待续跑: 会话 ${outcome.plan.sessionId} 第 ${outcome.plan.step} 步 (provider 配置后触发)")
                    }
                    is ResumeOutcome.Prompt -> {
                        session.messages.value = session.messages.value + ChatMessageUi.System(outcome.hint)
                    }
                    ResumeOutcome.None -> Unit
                }

                // ── 侧栏会话记录落账 ──
                val preview = msgs.firstOrNull()?.let {
                    when (it) {
                        is ChatMessageUi.User -> it.content.take(40)
                        is ChatMessageUi.Agent -> it.content.take(40)
                        else -> ""
                    }
                } ?: ""
                val sessionId = read.sessionId ?: "sess_restored"
                val record = SessionPersistenceService.SessionRecord(
                    id = sessionId, title = preview.ifBlank { "会话" }, preview = preview,
                    timestamp = file.lastModified(), messageCount = msgs.size,
                    agentName = activeAgentName
                )
                val existingIndex = history.indexOfFirst { it.id == sessionId }
                val updated = if (existingIndex >= 0) {
                    history.toMutableList().also { it[existingIndex] = record }
                } else {
                    (history.filter { it.id != sessionId } + record).takeLast(100)
                }
                setHistory(updated)
                persistHistory(updated)
                setCurrentSessionId(sessionId)
                true
            }
        }
    } catch (_: Exception) {
        deleteFile()
        false
    }
}
