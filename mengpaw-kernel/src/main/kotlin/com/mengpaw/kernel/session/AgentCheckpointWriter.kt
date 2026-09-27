// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.harness.CheckpointStatus
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType

/**
 * ReAct 循环的检查点写入器 (P0-1/P0-2 接线) — 从 [com.mengpaw.kernel.AgentReActLoop] 抽出的
 * 纯落盘职责 (loop 骨架保持 ≤400 行)。
 *
 * ## 写入时机 (定案)
 * - **每步**: 工具批次执行完毕 + Observation 已入库 + `state.step++` 之后写一条
 *   [CheckpointStatus.RUNNING], 携带当步的 [Message] 快照。仅当步号未变时跳过重复写
 *   (同一批多 Action 或幻觉拒绝重来不会重复落同一份档)。
 * - **终态**: 每次运行恰好写一条终态 ([CheckpointStatus.COMPLETED] / [CheckpointStatus.FAILED]),
 *   由 loop 的 `finally` 统一收口 — 正常答案 / 退化输出 / 空响应 / 步数耗尽 / 只思考不行动 /
 *   循环检测 / 连续失败 / 会话完整性失败 / 用户取消 / 任意异常全部覆盖。
 *
 * ## 为什么用 `sessionId` 作为唯一键
 * 续跑要按会话找回进度, 且恢复方会用 [History.restoreSession] 沿用**同一个 sessionId** 重建
 * 会话 — 键一致才能让 `loadLatest` 一次命中, 不产生"恢复后又是一套新 id"的孤儿档。
 *
 * ## 失败不得影响主任务
 * 检查点是可靠性增强, 不是新的失败点: 所有落盘/清理异常一律 try/catch 并
 * [ErrorCollector.report] 上报 (与 [CheckpointManager] 内部容错语义一致), 绝不向循环抛出。
 */
internal class AgentCheckpointWriter(
    private val checkpointManager: CheckpointManager,
    private val toolIntentLog: ToolIntentLog = ToolIntentLog()
) {

    /** 上次写 RUNNING 的步号 — 同一步重复调用跳过 (防同批多 Action 重复落盘)。 */
    private var lastWrittenStep: Int = NOT_WRITTEN

    /** agent / model 上下文 — 与既有检查点 context 键保持一致 (诊断用, 不参与恢复判据)。 */
    private var context: Map<String, String> = emptyMap()

    /** 终态是否已写 — 保证每次运行恰好一条终态档。 */
    private var terminalWritten = false

    /**
     * 落终态检查点 (每次运行恰好一条)。
     *
     * @param outcome `true to 最终答案` = COMPLETED; `false to 错误/终止文本` = FAILED。
     *   null 时按 FAILED 兜底 (循环未给出结论就退出的异常路径)。
     * @param step 已消耗步数 (RUNNING 档的步号; 传 -1 表示未知 → 沿用上次 RUNNING 的步号)
     * @param cancelledMark [outcome] 第二项等于该标记时, 终止原因记 "cancelled"
     *   (用户停止/作用域取消, 与崩溃/模型错误区分)。
     */
    suspend fun finalize(
        sessionId: String,
        task: String,
        outcome: Pair<Boolean, String>?,
        step: Int,
        messages: List<Message>,
        cancelledMark: String
    ) {
        val success = outcome?.first == true
        val text = outcome?.second
        val reason = when {
            text == cancelledMark -> "cancelled"
            success -> null
            text.isNullOrBlank() -> "unknown"
            else -> "terminated"
        }
        writeTerminal(
            sessionId = sessionId,
            step = if (step >= 0) step else lastWrittenStep.coerceAtLeast(0),
            task = task,
            status = if (success) CheckpointStatus.COMPLETED else CheckpointStatus.FAILED,
            terminationReason = reason,
            answer = text?.takeIf { success },
            messages = messages
        )
    }

    /** 设置上下文 (agentName / modelName) — 由循环在会话装配后调用一次。 */
    fun bindContext(agentName: String, modelName: String) {
        context = mapOf("agentName" to agentName, "modelName" to modelName)
    }

    /**
     * 写 RUNNING 检查点 (每步一次)。[step] 与上次相同则跳过。
     * @param messages 当步的会话消息快照 (由调用方从 SessionManager 取, 是恢复的唯一事实源)
     */
    suspend fun writeRunning(
        sessionId: String,
        step: Int,
        task: String,
        messages: List<Message>
    ) {
        if (step == lastWrittenStep) return
        try {
            checkpointManager.save(Checkpoint(
                sessionId = sessionId,
                step = step,
                remainingTask = task,
                context = context,
                status = CheckpointStatus.RUNNING,
                messages = messages
            ))
            lastWrittenStep = step
            checkpointManager.cleanup(sessionId, keep = DEFAULT_KEEP)
        } catch (e: Exception) {
            reportFailure("writeRunning", sessionId, e)
        }
    }

    /**
     * 写终态检查点。**恰好一次**: 已写过则直接返回 (由 loop 的 finally 调用)。
     *
     * @param status [CheckpointStatus.COMPLETED] (正常收尾/退化输出) 或 [CheckpointStatus.FAILED]
     * @param terminationReason 机器可读终止原因 (max_steps / loop_detected / cancelled / agent_error ...)
     * @param answer 最终答案文本 (FAILED 时为 null)
     * @param messages 终态消息快照 — 让续跑能看到最后一步的 Observation
     */
    suspend fun writeTerminal(
        sessionId: String,
        step: Int,
        task: String,
        status: CheckpointStatus,
        terminationReason: String?,
        answer: String?,
        messages: List<Message>
    ) {
        if (terminalWritten) return
        terminalWritten = true
        try {
            checkpointManager.save(Checkpoint(
                sessionId = sessionId,
                step = step,
                remainingTask = task,
                context = context,
                status = status,
                messages = messages,
                terminationReason = terminationReason,
                answer = answer
            ))
            checkpointManager.cleanup(sessionId, keep = DEFAULT_KEEP)
        } catch (e: Exception) {
            reportFailure("writeTerminal($terminationReason)", sessionId, e)
        }
    }

    /**
     * 续跑前处理"未确认完成的工具调用" (防重复副作用)。
     *
     * ## 为什么禁止自动重放
     * [ToolIntentLog] 的 PENDING 记录含义是"**副作用可能已经发生**" (工具已执行完、完成记录
     * 尚未落盘就崩溃)。此时若框架自动重放这一步, 重复写文件/发消息/下单等副作用无法回滚;
     * 而"跳过不执行"又会让任务凭空少一步。二者都不能替 Agent 决定 — 故只注入一条**事实提示**,
     * 把判定权交回模型: 先核对实际结果 (读文件/查状态/查输出), 再决定是否需要重试。
     *
     * 提示为普通 system 消息 ([Message.localOnly] = false): 它是当前任务上下文的一部分,
     * 必须让模型看见并据此行动 (localOnly 消息只作框架内部元数据, 不进 LLM 请求)。
     *
     * @return 实际注入的提示文本; 无 PENDING 意图或注入失败返回 null。
     */
    fun injectPendingIntentNote(sessionId: String, sessionManager: SessionManager): String? {
        val pending = try {
            toolIntentLog.pending(sessionId)
        } catch (e: Exception) {
            reportFailure("pendingIntents", sessionId, e)
            return null
        }
        if (pending.isEmpty()) return null
        val names = pending.map { it.toolName.ifBlank { "(未知工具)" } }.distinct()
        val note = pendingIntentNote(names)
        try {
            sessionManager.addMessage(sessionId, Message("system", note))
            return note
        } catch (e: Exception) {
            reportFailure("injectPendingIntentNote", sessionId, e)
            return null
        }
    }

    /**
     * 未确认完成的工具调用提示文案 (纯函数, 供测试直接断言)。
     * 文案刻意"只给事实 + 要求先核对", 不含"请重试/请继续"这类可能诱发重复副作用的指令。
     */
    fun pendingIntentNote(toolNames: List<String>): String =
        "上一轮以下工具调用未确认完成: ${toolNames.joinToString("、")}。" +
            "请先核对实际结果（读文件/查状态/查输出）再决定是否重试，不要直接重复执行。"

    private fun reportFailure(stage: String, sessionId: String, e: Exception) {
        KernelLog.w(TAG, "检查点 $stage 失败 (会话 $sessionId): ${e.message}")
        try {
            ErrorCollector.report(
                ErrorType.IO_ERROR, "AgentCheckpointWriter.$stage",
                e.message ?: "(no message)", throwable = e, sessionId = sessionId
            )
        } catch (_: Exception) {
            // ErrorCollector 自身故障不得再抛 — 检查点写失败不中断主任务
        }
    }

    private companion object {
        const val TAG = "AgentCheckpointWriter"

        /** 未写过的哨兵 (步号从 0 起, -1 不会与真实步号冲突)。 */
        const val NOT_WRITTEN = -1

        /** 保留份数 — 与 CheckpointManager 默认一致 (恢复只需最近一份)。 */
        const val DEFAULT_KEEP = 3
    }
}