// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.concise

import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.PluginStatus

/**
 * 言简意赅 Middleware — 只动提示词前缀的温和简洁引导。
 *
 * 由壳层 AgentSessionFactory 组装进 middleware 链（放链尾），每次
 * refreshSystemPrompt() 时执行。插件未激活（disablePlugin 停用）时原样返回，
 * 实现"可开关、无需重接链"。
 *
 * 变换原则（v0.28.1 收敛为"只动前缀"）：
 * - 不再删除强要求句（"每一步必须输出完整 Thought → Action → Action Input 序列"）—
 *   那是输出格式规范的一部分，删掉后模型不再分步思考、直接蹦 Final Answer，
 *   气泡思考过程(流式输出)消失
 * - 不再追加反 Markdown 装饰约束 — 那会强制模型输出纯文本，本地与接收端
 *   Markdown 渲染全部失效
 * - 只在提示词开头注入一行温和的简洁引导（幂等守卫，防重复注入），
 *   既保留简洁意图，又不破坏流式分步与 Markdown 格式
 */
/**
 * 上次变换是否实际注入了简洁引导 — 模板失配自检（提示词模板升级后
 * 引导句失配则静默失效, 供 ConcisePlugin.status 如实展示而非"✅ 生效"误报）。
 */
@Volatile
var lastTransformRemovedSentence: Boolean = false
    private set

val ConciseMiddleware: AgentMiddleware = AgentMiddleware { prompt, _ ->
    // 开关: 插件未 ACTIVE 时原样返回（停用即恢复原提示词）
    if (PluginManager.globalInstance.status(ConcisePlugin.PLUGIN_ID) != PluginStatus.ACTIVE) {
        return@AgentMiddleware prompt
    }
    // 只动前缀 — 按语言分支注入一行简洁引导 + 幂等守卫（防多次 refresh 重复注入）
    val conciseLine =
        if (prompt.contains("Think and respond in English")) EN_CONCISE_LINE
        else ZH_CONCISE_LINE
    if (conciseLine !in prompt) {
        lastTransformRemovedSentence = true
        "$conciseLine\n\n$prompt"
    } else {
        lastTransformRemovedSentence = false
        prompt
    }
}

private const val ZH_CONCISE_LINE =
    "回答保持简洁：直接给结论，不重复、不冗长；Markdown 格式（标题/列表/代码块）照常使用，保持可读性。"

private const val EN_CONCISE_LINE =
    "Keep replies concise: give conclusions directly, don't repeat or pad. Use Markdown (headings/lists/code blocks) as usual for readability."
