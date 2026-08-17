// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.flow.update

/**
 * 思考过程容器写入器 (v0.40.2 重构)。
 *
 * 一次任务 = 单一过程容器 (ThinkingProcess: 思考/调用/观察循环, 折叠) + 最终答案
 * (FinalAnswer) 气泡。时间轴主导: 思考一次性写入容器 → Action 出现插入折叠工具行 →
 * 工具完成挂观察全文 → 检测到 Final Answer 自动折叠容器 + 答案气泡流式。
 *
 * v0.40.2 删除 RunningStepTracker (index/ref 身份追踪): 该追踪器在消息列表被
 * 重建/恢复 (进程死亡恢复) 后身份失效, 是"思考中... xxs 气泡永远 isRunning"的
 * 结构性诱因之一。任务串行执行, 本任务的容器/气泡恒为列表中最后一个对应类型 —
 * 直接按类型定位最后一条, 无跨线程共享索引, 天然幂等。
 * 线程纪律: 所有写入经 session.messages.update (MutableStateFlow CAS), engine
 * 回调线程 / 播放协程 / 主协程三方安全。
 */
internal class ThinkingProcessWriter(
    private val session: AgentSession,
    private val modePrefix: String?,
    private val agentRef: String?,
) {

    /** 任务开始: 创建过程容器 (展开, 思考中)。 */
    fun start() {
        val msg = ChatMessageUi.ThinkingProcess(
            steps = emptyList(), isRunning = true, collapsed = false,
            executionMode = modePrefix, agentRef = agentRef
        )
        session.messages.value = session.messages.value + msg
    }

    /**
     * 流式思考一次性显示 — 按 roundId 路由: 同一轮覆盖同一步, 跨轮另起新 step。
     * (v0.36.3 修复: 原实现用 last.tools 非空判轮界, 当前轮 addTool 插入工具行后,
     *  同轮后续思考增量全被误判为"上一轮已固化"而另起 step。)
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

    /** 工具提前通知: 完整 "Action: <tool>" 行 → 插入折叠工具行。
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

    /**
     * 检测到 Final Answer 开始: 过程容器自动折叠 (isRunning=false), 创建最终答案气泡。
     * 幂等: 本任务容器已折叠且已有 FinalAnswer 时直接返回 — 防 onDelta 与引擎返回
     * 兜底双触发产生两个答案气泡。
     */
    fun beginFinalAnswer() {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val tpIdx = mutable.indexOfLast { it is ChatMessageUi.ThinkingProcess }
            val tp = mutable.getOrNull(tpIdx) as? ChatMessageUi.ThinkingProcess
            // 本任务已闭环: 容器已折叠 + 末尾已是 FinalAnswer → 幂等返回
            if (tp != null && tp.collapsed && !tp.isRunning &&
                mutable.lastOrNull() is ChatMessageUi.FinalAnswer
            ) {
                return@update current
            }
            if (tp != null) {
                mutable[tpIdx] = tp.copy(collapsed = true, isRunning = false)
            }
            if (mutable.lastOrNull() is ChatMessageUi.FinalAnswer) return@update current
            val fa = ChatMessageUi.FinalAnswer(
                content = "", isRunning = true,
                executionMode = modePrefix, agentRef = agentRef
            )
            mutable.add(fa)
            mutable
        }
    }

    /** 最终答案流式更新 (覆盖 — 播放器每 tick 推完整文本)。 */
    fun pushFinal(text: String) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val idx = mutable.indexOfLast { it is ChatMessageUi.FinalAnswer }
            if (idx >= 0) {
                mutable[idx] = (mutable[idx] as ChatMessageUi.FinalAnswer).copy(content = text)
            }
            mutable
        }
    }

    /**
     * 最终答案定型 (applyFinalResult 调用): 折叠/停止思考容器, 写完整答案并退出
     * 运行态; 无 FinalAnswer (异常路径) 时兜底追加 Agent 气泡。
     */
    fun finalize(answer: String) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            stopProcessContainer(mutable)
            val idx = mutable.indexOfLast { it is ChatMessageUi.FinalAnswer }
            if (idx >= 0) {
                mutable[idx] = (mutable[idx] as ChatMessageUi.FinalAnswer)
                    .copy(content = answer, isRunning = false)
            } else {
                mutable.add(ChatMessageUi.Agent(answer, executionMode = modePrefix, agentRef = agentRef))
            }
            mutable
        }
    }

    /**
     * 失败兜底 (applyError 调用): 最终答案气泡替换为错误消息 (若存在);
     * 否则过程容器收尾 + 追加 Agent 错误。
     */
    fun fail(errorMsg: String) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val idx = mutable.indexOfLast { it is ChatMessageUi.FinalAnswer }
            if (idx >= 0) {
                mutable[idx] = (mutable[idx] as ChatMessageUi.FinalAnswer)
                    .copy(content = errorMsg, isRunning = false)
            } else {
                stopProcessContainer(mutable)
                mutable.add(ChatMessageUi.Agent(errorMsg, executionMode = modePrefix, agentRef = agentRef))
            }
            mutable
        }
    }

    /** 插件缺失建议气泡 — 追加到消息列表末尾 (applyFinalResult 调用)。 */
    fun appendSuggestion(suggestion: PluginSuggestion) {
        session.messages.value = session.messages.value + ChatMessageUi.Suggestion(suggestion)
    }

    /** 更新过程容器 steps (按类型定位最后一条 — 任务串行, 恒为本任务容器)。 */
    private fun updateProcess(transform: (List<ChatMessageUi.ProcessStep>) -> List<ChatMessageUi.ProcessStep>) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val tpIdx = mutable.indexOfLast { it is ChatMessageUi.ThinkingProcess }
            if (tpIdx >= 0) {
                val prev = mutable[tpIdx] as ChatMessageUi.ThinkingProcess
                mutable[tpIdx] = prev.copy(steps = transform(prev.steps))
            }
            mutable
        }
    }

    /** 停止/折叠运行中的思考容器 — 定型与失败兜底共用。 */
    private fun stopProcessContainer(mutable: MutableList<ChatMessageUi>) {
        val tpIdx = mutable.indexOfLast { it is ChatMessageUi.ThinkingProcess }
        if (tpIdx >= 0) {
            val tp = mutable[tpIdx] as ChatMessageUi.ThinkingProcess
            if (tp.isRunning || !tp.collapsed) {
                mutable[tpIdx] = tp.copy(isRunning = false, collapsed = true)
            }
        }
    }
}
