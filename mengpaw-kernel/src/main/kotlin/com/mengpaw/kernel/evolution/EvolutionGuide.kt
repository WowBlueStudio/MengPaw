// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

/**
 * 进化省察引导入口 — 让 Agent 在失败后按金字塔四层向自己提问。
 *
 * 不新建第二个 LLM:引导以 system 消息注入主对话, Agent 自己完成
 * L1 事实 → L2 归因 → L3 用户视角(检索用户反应档案) → L4 进化(错误四分法处置)。
 *
 * 薄分派: 委托给当前生效的 [EvolutionProvider] ([EvolutionProviderRegistry.active]).
 * 默认实现 = [EvolutionEngine] (轻/深分级引导), 第三方可整体替换。
 */
object EvolutionGuide {

    /** 每会话引导注入上限 — 防刷屏, 保上下文。 */
    const val MAX_INJECTIONS = 3

    /**
     * 失败后生成引导片段。基于失败模式库最新记录分级 (轻/深)。
     * 返回 null 表示无需注入。
     */
    fun buildFragment(agentName: String?, command: String, message: String): String? =
        EvolutionProviderRegistry.active().buildFragment(agentName, command, message)

    /** 会话开始时的绩效提醒 — 有未修正复现模式时注入一次 (无复现时零开销)。 */
    fun buildSessionBrief(agentName: String?): String? =
        EvolutionProviderRegistry.active().buildSessionBrief(agentName)
}
