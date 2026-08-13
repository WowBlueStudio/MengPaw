// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.flow.update

/**
 * 思考过程容器写入器 (v0.34.3 气泡 UI 重构, 替代 StepBubbleWriter)。
 *
 * 一次任务 = 单一过程容器 (ThinkingProcess: 思考/调用/观察循环, 折叠) + 最终答案
 * (FinalAnswer) 气泡。时间轴主导: 思考流式写入容器 → Action 出现插入折叠工具行 →
 * 工具完成挂观察全文 → 检测到 Final Answer 自动折叠容器 + 答案气泡流式。
 *
 * 线程纪律沿用 RunningStepTracker: engine 回调线程 (onDelta/onStep) / 播放协程
 * (Default) / 主协程三方读写, @Volatile ref 跨线程可见。
 */
internal class ThinkingProcessWriter(
    private val session: AgentSession,
    private val modePrefix: String?,
    private val agentRef: String?,
) {
    val tracker = RunningStepTracker()

    /** 任务开始: 创建过程容器 (展开, 思考中)。 */
    fun start() {
        val msg = ChatMessageUi.ThinkingProcess(
            steps = emptyList(), isRunning = true, collapsed = false,
            executionMode = modePrefix, agentRef = agentRef
        )
        tracker.ref = msg
        tracker.index = session.messages.value.size
        session.messages.value = session.messages.value + msg
    }

    /**
     * 流式思考更新 — 播放器每次推完整显示文本, 覆盖当前轮 thought。
     * 按 roundId 路由: 同一轮 (roundId 相同) 增量覆盖同一步, 跨轮 (roundId
     * 递增) 才另起新 step — 修复 v0.36.3 回归: 原实现用 last.tools 非空判轮界,
     * 当前轮 addTool 插入工具行后, 同轮后续思考增量全被误判为"上一轮已固化"
     * 而另起 step, 导致前几轮思考只显示 1~3 字并产生重复 step。
     */
    fun pushThought(text: String, roundId: Long) {
        updateProcess { steps ->
            val newSteps = steps.toMutableList()
            val idx = newSteps.indexOfLast { it.roundId == roundId }
            if (idx < 0) {
                newSteps.add(ChatMessageUi.ProcessStep(roundId = roundId, thought = text))
            } else {
                newSteps[idx] = newSteps[idx].copy(thought = text)
            }
            newSteps
        }
    }

    /** 工具提前通知: 流式检测到完整 "Action: <tool>" 行 → 插入折叠工具行。
     *  按 roundId 挂到对应 step — 思考增量未到 (突发流) 时先建 step, 后续
     *  pushThought 同轮更新, 不再拆成两个 step。 */
    fun addTool(command: String, roundId: Long) {
        updateProcess { steps ->
            val newSteps = steps.toMutableList()
            val tool = ChatMessageUi.ProcessTool(command = command, actionInput = "", observation = "")
            val idx = newSteps.indexOfLast { it.roundId == roundId }
            if (idx < 0) {
                newSteps.add(ChatMessageUi.ProcessStep(roundId = roundId, tools = listOf(tool)))
            } else {
                newSteps[idx] = newSteps[idx].copy(tools = newSteps[idx].tools + tool)
            }
            newSteps
        }
    }

    /** 工具完成: 按命令名匹配最后一个未完成工具, 挂观察全文 + 成败。 */
    fun completeTool(commandLine: String, observation: String, isError: Boolean) {
        val name = commandLine.trim().split(" ").firstOrNull() ?: return
        val input = commandLine.trim().removePrefix(name).trim()
        updateProcess { steps ->
            val newSteps = steps.toMutableList()
            val last = newSteps.lastOrNull() ?: return@updateProcess steps
            val tools = last.tools.toMutableList()
            val idx = tools.indexOfLast { it.command == name && it.observation.isEmpty() }
            if (idx >= 0) {
                tools[idx] = tools[idx].copy(observation = observation, isError = isError, actionInput = input)
                newSteps[newSteps.lastIndex] = last.copy(tools = tools)
            }
            newSteps
        }
    }

    /** 检测到 Final Answer 开始: 过程容器自动折叠 (isRunning=false), 创建最终答案气泡。 */
    fun beginFinalAnswer() {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val pidx = resolveRunningIndex(mutable, tracker.index, tracker.ref)
            if (pidx >= 0 && mutable[pidx] is ChatMessageUi.ThinkingProcess) {
                mutable[pidx] = (mutable[pidx] as ChatMessageUi.ThinkingProcess)
                    .copy(collapsed = true, isRunning = false)
            }
            val fa = ChatMessageUi.FinalAnswer(
                content = "", isRunning = true,
                executionMode = modePrefix, agentRef = agentRef
            )
            tracker.ref = fa
            tracker.index = mutable.size
            mutable.add(fa)
            mutable
        }
    }

    /** 最终答案流式更新 (覆盖 — 播放器每次推完整文本)。 */
    fun pushFinal(text: String) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val idx = resolveRunningIndex(mutable, tracker.index, tracker.ref)
            if (idx >= 0 && mutable[idx] is ChatMessageUi.FinalAnswer) {
                val updated = (mutable[idx] as ChatMessageUi.FinalAnswer).copy(content = text)
                tracker.ref = updated
                tracker.index = idx
                mutable[idx] = updated
            }
            mutable
        }
    }

    /** 最终答案定型 (applyFinalResult 调用)。 */
    fun finalize(answer: String) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val idx = resolveRunningIndex(mutable, tracker.index, tracker.ref)
            if (idx >= 0 && mutable[idx] is ChatMessageUi.FinalAnswer) {
                val updated = (mutable[idx] as ChatMessageUi.FinalAnswer)
                    .copy(content = answer, isRunning = false)
                tracker.ref = updated
                tracker.index = idx
                mutable[idx] = updated
            } else {
                // 兜底: 无 FinalAnswer (如纯文本最终轮未经 beginFinalAnswer) → 追加 Agent
                mutable.add(ChatMessageUi.Agent(answer, executionMode = modePrefix, agentRef = agentRef))
            }
            mutable
        }
    }

    /** 失败兜底: 最终答案气泡替换为错误消息 (若存在); 否则过程容器后追加 Agent 错误。 */
    fun fail(errorMsg: String) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val idx = resolveRunningIndex(mutable, tracker.index, tracker.ref)
            if (idx >= 0 && mutable[idx] is ChatMessageUi.FinalAnswer) {
                val updated = (mutable[idx] as ChatMessageUi.FinalAnswer)
                    .copy(content = errorMsg, isRunning = false)
                tracker.ref = updated
                tracker.index = idx
                mutable[idx] = updated
            } else {
                // 过程容器收尾 + 追加错误
                val pidx = mutable.indexOfLast { it is ChatMessageUi.ThinkingProcess && it.isRunning }
                if (pidx >= 0) mutable[pidx] = (mutable[pidx] as ChatMessageUi.ThinkingProcess)
                    .copy(isRunning = false)
                mutable.add(ChatMessageUi.Agent(errorMsg, executionMode = modePrefix, agentRef = agentRef))
            }
            mutable
        }
    }

    /** 更新过程容器 steps (跨线程安全)。 */
    private fun updateProcess(transform: (List<ChatMessageUi.ProcessStep>) -> List<ChatMessageUi.ProcessStep>) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val idx = resolveRunningIndex(mutable, tracker.index, tracker.ref)
            if (idx >= 0 && mutable[idx] is ChatMessageUi.ThinkingProcess) {
                val prev = mutable[idx] as ChatMessageUi.ThinkingProcess
                val updated = prev.copy(steps = transform(prev.steps))
                tracker.ref = updated
                tracker.index = idx
                mutable[idx] = updated
            }
            mutable
        }
    }
}
