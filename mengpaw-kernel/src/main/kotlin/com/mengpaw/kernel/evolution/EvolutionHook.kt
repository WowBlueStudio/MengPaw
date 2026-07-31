// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorEntry

/**
 * 进化系统钩子 — 钩子归系统, 失败事件自动流入 [EvolutionStore]。
 *
 * 挂接在 [ErrorCollector.onReport]: Pipeline.failAudit、AgentEngine 的
 * TOOL_CALL_FAILED / LOOP_DETECTED / AGENT_CRASH 全部经 ErrorCollector.report,
 * 因此这里一处挂接覆盖所有失败来源, 无需改动任何调用点。
 *
 * 用户纠正(纠正识别在 shell 层)经 [recordCorrection] 独立写入用户反应档案。
 */
object EvolutionHook {

    @Volatile
    private var installed = false

    /** 挂接失败回调 — 幂等, 可多次调用。 */
    fun install() {
        if (installed) return
        installed = true
        ErrorCollector.onReport = { entry -> onFailure(entry) }
    }

    private fun onFailure(entry: ErrorEntry) {
        try {
            val command = entry.metadata["command"] ?: entry.source
            val errorCode = entry.metadata["errorCode"] ?: entry.type.name
            EvolutionStore.recordFailure(
                agentName = entry.agentName,
                command = command,
                errorCode = errorCode,
                message = entry.message,
                source = entry.source
            )
        } catch (_: Exception) { /* 钩子永不崩溃 */ }
    }

    /**
     * 用户纠正/撤回 → 用户反应档案(用户分身数据源)。
     * 由 shell 层纠正识别调用。
     */
    fun recordCorrection(agentName: String?, correction: String, contextSnippet: String, task: String) {
        try {
            EvolutionStore.recordCorrection(agentName, correction, contextSnippet, task)
        } catch (_: Exception) { /* 钩子永不崩溃 */ }
    }
}
