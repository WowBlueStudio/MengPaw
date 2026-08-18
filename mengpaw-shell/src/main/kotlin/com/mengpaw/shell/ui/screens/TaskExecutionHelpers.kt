// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务执行的独立辅助逻辑 (自 AgentViewModel 拆出 — 顶层函数, 逻辑不变):
 * 纠正识别 / 插件建议 / 自动摘要 / !bang 命令 / 最终答案落盘 / 错误兜底。
 */

/** 纠正信号词表 — 短消息(≤80 字) + 负向措辞 = 用户纠正/否定. (原 AgentViewModel 私有, 逻辑不变) */
private val CORRECTION_KEYWORDS = listOf(
    "不对", "错了", "不是这个", "不是这样", "做错了", "理解错了", "说错了",
    "重做", "重新做", "重新来", "再来一次", "再想想", "重新想",
    "算了", "别这样", "停一下", "不对吧", "没听懂", "我说的不是"
)

/**
 * 规则版纠正识别 — 命中后把"上一条 Agent 回复 + 用户纠正"切片写入用户反应档案,
 * 供 Agent 在 L3 用户视角提问时检索(用户分身数据源)。
 * 规则版先行; 后续可升级为 LLM 语义识别。 (原 AgentViewModel.detectCorrection, 逻辑不变)
 */
internal fun detectCorrection(task: String, agentRef: String?, session: AgentSession, activeAgentName: String) {
    try {
        if (task.length > 80) return
        if (CORRECTION_KEYWORDS.none { task.contains(it) }) return
        // 上下文切片: 最近一条 Agent 回复
        val snippet = session.messages.value.asReversed()
            .firstNotNullOfOrNull { msg ->
                when (msg) {
                    is ChatMessageUi.Agent -> msg.content
                    is ChatMessageUi.AgentWithTrace -> msg.finalContent
                    is ChatMessageUi.AgentStep -> msg.content
                    is ChatMessageUi.FinalAnswer -> msg.content
                    else -> null
                }
            }?.take(200) ?: ""
        com.mengpaw.kernel.evolution.EvolutionHook.recordCorrection(
            agentName = agentRef ?: activeAgentName,
            correction = task.take(200),
            contextSnippet = snippet,
            task = task.take(300)
        )
    } catch (_: Exception) { /* 识别永不崩溃 */ }
}

/**
 * 插件缺失建议 (P2 修复: 原硬编码 7 插件列表, 新增/改名需手动同步) —
 * 注册表动态判定, 比硬编码列表感知安装状态。 (原 AgentViewModel.checkMissingPlugin, 逻辑不变)
 */
internal fun checkMissingPlugin(output: String): PluginSuggestion? {
    val match = PluginViewModel.UNKNOWN_COMMAND_REGEX.find(output) ?: return null
    val namespace = match.groupValues[1]
    val pluginId = "$namespace-plugin"

    val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
    // 已安装的插件不再提示"安装" — 注册表动态判定, 比硬编码列表感知安装状态
    if (pm.get(pluginId) != null) return null

    // 内核内置能力 (memory → agent.memory.* 非插件, 注册表查不到): 保留专属映射
    if (namespace == "memory") {
        return PluginSuggestion("memory", "agent.memory", "Memory System",
            "三轨记忆 (内核): keep/record/read/search/stats/write", "agent.memory.*")
    }

    // 动态来源: 捆绑插件注册表 (单一事实源 — 新增捆绑插件自动获得提示能力)
    val info = com.mengpaw.shell.PluginRegistrar.BUILTIN_PLUGIN_INFO[pluginId] ?: return null
    return PluginSuggestion(
        namespace = namespace,
        pluginId = pluginId,
        pluginName = info.first,
        description = info.second,
        missingCommand = "$namespace.*"
    )
}

/**
 * 自动摘要: 对话结束后提取关键信息存入 memory (原 AgentViewModel.submitTask 尾段 launch, 逻辑不变).
 */
internal fun launchAutoSummary(
    scope: CoroutineScope,
    session: AgentSession,
    task: String,
    displayResult: String,
    activeAgentName: String,
) {
    scope.launch {
        try {
            val summaryPrompt = """
提取以下对话中用户提到的关键信息（偏好、需求、决策、技术环境），
用 1-2 句中文摘要，只提取值得长期记住的事实。

用户消息: ${task.take(300)}
助手结果: ${displayResult.take(300)}

摘要：""".trimIndent()
            val summary = session.provider.complete(summaryPrompt).take(200)
            if (summary.isNotBlank() && summary.length > 10) {
                withContext(Dispatchers.IO) {
                    com.mengpaw.kernel.agent.AgentDocs.appendMemory(activeAgentName, summary)
                }
            }
        } catch (_: Exception) {}
    }
}

/**
 * 执行 "!command" — 绕过 Agent/LLM 本地直接执行 (原 AgentViewModel.runBangCommand, 逻辑不变).
 * 用户消息 (含 ! 前缀) 原样保留在历史中; 结果以 CommandResult 气泡追加。
 */
internal fun runBangCommand(scope: CoroutineScope, session: AgentSession, original: String, command: String) {
    // 原子 CAS 入列用户消息, 保留含 ! 的完整原文
    session.messages.update { it + ChatMessageUi.User(original) }
    scope.launch(Dispatchers.IO) {
        val result = try {
            session.engine.executeCommand(command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ExecutionResult.fail(e.message ?: "未知错误", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        // v0.42.3 用户定案: 空结果不返回气泡; 成功=灰气泡, 失败=红气泡
        bangResultMessage(result)?.let { text ->
            session.messages.update { it + ChatMessageUi.CommandResult(text, isError = !result.success) }
        }
    }
}

/**
 * bang 命令结果 → 气泡文本 (v0.42.3 用户定案):
 * - 成功且输出为空/空白 → null (不追加气泡)
 * - 成功有输出 → 输出文本 (CommandResultBubble 灰气泡)
 * - 失败 → 错误文本 (红气泡), error 为空时兜底 "命令执行失败"
 */
internal fun bangResultMessage(result: com.mengpaw.kernel.cli.ExecutionResult): String? {
    val text = if (result.success) {
        result.output
    } else {
        // 失败必返回红气泡 — error 为空/空白时兜底, 不落入"空值不返回"分支
        result.error?.takeIf { it.isNotBlank() } ?: "命令执行失败"
    }
    // 仅成功且输出为空时跳过气泡 (v0.42.3 用户定案)
    if (result.success && text.isBlank()) return null
    // 空判定用 isBlank, 返回保留原文 (不 trim, 忠实呈现 Shell 输出)
    return if (text.length > 4000) text.take(4000) + "\n\n...(输出过长, 已截断)" else text
}

/**
 * 最终答案替换 + 插件建议 (原 submitTask 尾段 messages.update 块, 逻辑不变):
 * 经 ThinkingProcessWriter.finalize 定型 FinalAnswer 气泡 (无气泡时兜底追加 Agent);
 * 命中未知命令时追加 Suggestion 气泡并通知 PluginViewModel。
 */
internal fun applyFinalResult(
    writer: ThinkingProcessWriter,
    displayResult: String,
    result: String,
    modePrefix: String?,
    agentRef: String?,
    pluginViewModel: PluginViewModel?,
) {
    // v0.40.2: 定型交给 writer — 幂等折叠思考容器 + 写完整答案 + 退出运行态
    writer.finalize(displayResult)

    val suggestion = checkMissingPlugin(result)
    if (suggestion != null && pluginViewModel != null) {
        writer.appendSuggestion(suggestion)
        pluginViewModel.suggestPluginForCommand(result)
    }
}

/**
 * 执行失败兜底 (原 submitTask catch(Throwable) 块, 逻辑不变):
 * 终止播放协程并等待, 写入错误消息, 恢复 loopMode 与 running/input 状态,
 * 立即落盘并处理下一条待办任务。安全网: 防进程崩溃, 优雅降级为错误消息。
 */
internal suspend fun applyError(
    e: Throwable,
    session: AgentSession,
    writer: ThinkingProcessWriter,
    savedLoopMode: LoopMode,
    playbackJob: Job?,
    modePrefix: String?,
    agentRef: String?,
    chat: SessionChatController,
    inputTagManager: InputTagManager,
    sessionPersistence: SessionPersistenceService,
    onProcessNext: () -> Unit,
) {
    KernelLog.w("AgentViewModel", "Task execution failed: ${e.message}")
    // Stop engine to prevent stale state on retry
    try { session.engine.stop() } catch (_: Exception) {}
    // 终止播放协程并等待 — 防止 Default 线程晚到 tick 覆盖错误消息
    playbackJob?.cancel()
    playbackJob?.join()
    val errorMsg = if (e is OutOfMemoryError) {
        "内存不足，任务已中断。请清理会话历史后重试。"
    } else {
        "执行出错：${e.message?.take(120) ?: "未知错误"} — 已完成的工作已自动记录，继续对话可恢复进度。"
    }
    // v0.34.3: 最终答案气泡 (若存在) 替换为错误; 否则过程容器收尾 + 追加错误
    writer.fail(errorMsg)
    // 恢复原始 loopMode
    inputTagManager.loopMode = savedLoopMode
    // Fully sync all running/input state
    session.isRunning.value = false
    chat.isRunningFlow.value = false
    session.inputEnabled.value = true
    chat.inputEnabledFlow.value = true
    // 错误回复同样立即落盘 (进程死亡恢复点)
    sessionPersistence.saveCurrentSession()
    onProcessNext()
}
