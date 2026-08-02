// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.concise

import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.PluginStatus

/**
 * 言简意赅 Middleware — 去除系统提示词中的结构性输出干扰。
 *
 * 由壳层 AgentSessionFactory 组装进 middleware 链（放链尾），每次
 * refreshSystemPrompt() 时执行。插件未激活（disablePlugin 停用）时原样返回，
 * 实现"可开关、无需重接链"。
 *
 * 变换规则（中英两版同步维护）：
 * 1. 删除"必须输出完整的 Thought → Action → Action Input 序列"强要求句 —
 *    parse() 硬依赖只有 Action:/Final Answer: 两个标记，强要求句可安全移除
 * 2. 追加反 Markdown 装饰约束 — 回复默认纯文本，代码/命令/路径可用代码块
 */
val ConciseMiddleware: AgentMiddleware = AgentMiddleware { prompt, _ ->
    // 开关: 插件未 ACTIVE 时原样返回（停用即恢复原提示词）
    if (PluginManager.globalInstance.status(ConcisePlugin.PLUGIN_ID) != PluginStatus.ACTIVE) {
        return@AgentMiddleware prompt
    }
    var p = prompt
    // 1. 删除强要求句（原文精确匹配；文本若已变动则自然跳过，防御性）
    p = p.replace(ZH_FORCED_SEQUENCE, "").replace(EN_FORCED_SEQUENCE, "")
    // 2. 反 Markdown 约束 — 按语言分支 + 幂等守卫（防多次 refresh 重复注入）
    val (marker, plainText) =
        if (p.contains("Think and respond in English")) EN_MARKER to EN_PLAIN_TEXT
        else ZH_MARKER to ZH_PLAIN_TEXT
    if (plainText !in p) p = "$p\n\n$plainText"
    p
}

private const val ZH_MARKER = "使用中文思考和输出"
private const val EN_MARKER = "Think and respond in English"

/** 中文强要求句（PromptEngine.CHINESE_PROMPT 原文，trimIndent 后）。 */
private const val ZH_FORCED_SEQUENCE =
    "**关键**：每一步必须输出完整的 Thought → Action → Action Input 序列。不要只输出 Thought 就停止。只有在任务真正完成时才输出 Final Answer。"

/** 英文强要求句（PromptEngine.ENGLISH_PROMPT 原文，trimIndent 后）。 */
private const val EN_FORCED_SEQUENCE =
    "**Critical**: Every step MUST output the complete Thought → Action → Action Input sequence. Never stop after just a Thought. Only output Final Answer when the task is truly complete."

private const val ZH_PLAIN_TEXT =
    "回复默认用简洁纯文本，不要用 Markdown 标题（#）、加粗（**）或列表符号（-）装饰；代码、命令、路径可用代码块包裹。"

private const val EN_PLAIN_TEXT =
    "Reply in plain text by default. Do not decorate replies with Markdown headings (#), bold (**), or list bullets (-); code, commands, and paths may use code blocks."
