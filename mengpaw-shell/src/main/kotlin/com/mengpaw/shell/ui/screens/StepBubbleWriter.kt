// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.flow.update

/**
 * 步骤气泡写入器 (自 AgentViewModel.submitTask 的局部函数拆出):
 * 每个 ReAct 步骤一个独立气泡 — 运行中: 流式文本 → 工具完成 (onStep) 固化
 * (思考折叠头 + 工具结果正文) → 下一步占位; 最终答案 = 最后一步 (isFinal)。
 * 多 Action 批: 同 step 连续 onStep 合并 observation 到同一气泡。
 * 持有 [RunningStepTracker] 跨线程守卫 index/ref (主协程 / 引擎回调 / 播放协程三方读写)。
 */
internal class StepBubbleWriter(
    private val session: AgentSession,
    private val modePrefix: String?,
    private val agentRef: String?,
) {
    val tracker = RunningStepTracker()

    /** 前置占位气泡 (原 submitTask 中 "思考中..." 首步占位, 逻辑不变). */
    fun preinsert(step: Int, content: String) {
        val msg = ChatMessageUi.AgentStep(
            step = step, thought = "", action = null,
            content = content, isRunning = true,
            executionMode = modePrefix, agentRef = agentRef
        )
        tracker.ref = msg
        tracker.index = session.messages.value.size
        session.messages.value = session.messages.value + msg
    }

    /** 更新/追加运行中气泡 (原 pushStepDisplay, 逻辑不变). */
    fun push(step: Int, thought: String, action: String?, content: String, isFinal: Boolean = false) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val ridx = resolveRunningIndex(mutable, tracker.index, tracker.ref)
            val newMsg = if (ridx >= 0) {
                // 替换后同步 ref/index — 快路径恒命中 (v0.28.4)
                (mutable[ridx] as ChatMessageUi.AgentStep).copy(
                    step = step, thought = thought, action = action,
                    content = content, isRunning = true, isFinal = isFinal
                )
            } else {
                ChatMessageUi.AgentStep(
                    step, thought, action, content, isRunning = true, isFinal = isFinal,
                    executionMode = modePrefix, agentRef = agentRef
                )
            }
            val target = if (ridx >= 0) ridx else mutable.size
            tracker.ref = newMsg
            tracker.index = target
            if (ridx >= 0) mutable[ridx] = newMsg else mutable.add(newMsg)
            mutable
        }
    }

    /** 固化当前运行 step (思考折叠头 + 工具结果) 并创建下一步占位气泡 (原 completeStep, 逻辑不变). */
    fun complete(trace: com.mengpaw.kernel.AgentEngine.TraceStep) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val ridx = resolveRunningIndex(mutable, tracker.index, tracker.ref)
            if (ridx >= 0) {
                mutable[ridx] = (mutable[ridx] as ChatMessageUi.AgentStep).copy(
                    thought = trace.thought, action = trace.action,
                    content = trace.observation ?: "", isRunning = false
                )
            }
            val next = ChatMessageUi.AgentStep(
                trace.step + 1, "", null, "思考中...", isRunning = true,
                executionMode = modePrefix, agentRef = agentRef
            )
            tracker.ref = next
            tracker.index = mutable.size
            mutable.add(next)
            mutable
        }
    }

    /** 多 Action 批内合并: 同 step 后续 observation 追加到已固化气泡 (原 mergeBatchObservation, 逻辑不变). */
    fun merge(trace: com.mengpaw.kernel.AgentEngine.TraceStep) {
        session.messages.update { current ->
            val mutable = current.toMutableList()
            val idx = mutable.indexOfLast { it is ChatMessageUi.AgentStep && it.step == trace.step && !it.isRunning }
            if (idx >= 0) {
                val prev = mutable[idx] as ChatMessageUi.AgentStep
                val obs = if (prev.content.isBlank()) (trace.observation ?: "")
                    else prev.content + "\n\n" + (trace.observation ?: "")
                mutable[idx] = prev.copy(
                    content = obs,
                    thought = if (prev.thought.isBlank()) trace.thought else prev.thought
                )
            }
            mutable
        }
    }
}
