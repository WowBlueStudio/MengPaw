// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.llm.PromptEngine

/**
 * Localized error messages for the AgentEngine.
 *
 * @param key error message key
 * @param detail dynamic detail to insert into the message
 * @param agentLanguage the language to use for the message
 */
fun localizedError(key: String, detail: String, agentLanguage: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE): String = when (agentLanguage) {
    PromptEngine.AgentLanguage.CHINESE -> when (key) {
        "loop_detected" -> "错误：检测到命令循环 — '$detail' 已重复 3+ 次"
        "consecutive_failures" -> "错误：连续 $detail 次命令执行失败，Agent 可能陷入困境。请检查网络、权限或换个方式提问。"
        "max_steps" -> "已达到最大步数 ($detail)，未获得最终答案"
        "agent_error" -> "Agent 错误：$detail"
        "no_plan" -> "无法为任务生成计划：$detail"
        "session_corrupted" -> "会话数据完整性检查失败 ($detail)。请使用 agent.repair 修复后重试，或开启新会话。"
        "empty_response" -> "模型未返回任何内容（空响应），已自动重试仍无结果。请重试一次，或换个问法。"
        else -> detail
    }
    PromptEngine.AgentLanguage.ENGLISH -> when (key) {
        "loop_detected" -> "Error: Detected command loop — '$detail' repeated 3+ times"
        "consecutive_failures" -> "Error: $detail consecutive command failures. Agent may be stuck. Check network, permissions, or rephrase."
        "max_steps" -> "Max steps ($detail) reached without final answer"
        "agent_error" -> "Agent error: $detail"
        "no_plan" -> "Could not generate a plan for: $detail"
        "session_corrupted" -> "Session data integrity check failed ($detail). Run agent.repair or start a new conversation."
        "empty_response" -> "The model returned an empty response (retried automatically). Please try again or rephrase."
        else -> detail
    }
}
