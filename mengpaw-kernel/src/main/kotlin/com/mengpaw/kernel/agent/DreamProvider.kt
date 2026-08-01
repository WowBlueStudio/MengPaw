// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.llm.LlmProvider

/**
 * 梦境提供者 SPI — 让第三方可整体替换梦境模式实现 (输入组装 / LLM 提炼 / 文件整理)。
 *
 * 默认实现 = 内核 [DreamEngine] (行为零变化)。内置插件 plugin-dream 在 onInstall 时
 * 注册默认实现; 第三方插件可实现本接口并在 onInstall 时注册自己的实现 — 后注册者胜。
 *
 * 注册表: [DreamProviderRegistry] (内核持有, 插件零框架耦合 — 与 FrameworkAdapter 同模式)。
 */
interface DreamProvider {

    /** 提供者名 (调试/日志标识)。 */
    val providerName: String

    /** 梦境输入组装: 对话摘要 + 三轨记忆 + 档案 → LLM 上下文 (第三方可自定义输入)。 */
    suspend fun buildContext(agentName: String, scroll: ScrollContextManager?): String?

    /** LLM 提炼梦境: 上下文 → 洞察, 写入 {date}_dream.md。返回写入内容或 null。 */
    suspend fun refine(agentName: String, llmProvider: LlmProvider, scroll: ScrollContextManager?): String?

    /** 文件整理 (无 LLM): 备份 memory/backup/ → 摘录 → 到期删除。 */
    fun organize(agentName: String): DreamResult

    /** 梦境统计 (日志行数)。 */
    fun stats(): String

    /** 梦境历史 (最近 N 条, 从 {date}_dream.md 提取)。 */
    fun history(limit: Int = 10): String
}

/** 文件整理结果。 */
data class DreamResult(
    val memoriesReviewed: Int,
    val archived: Int,
    val message: String
)

/**
 * 梦境提供者注册表 — 内核默认实现兜底; 插件 (plugin-dream / 第三方) 注册覆盖。
 */
object DreamProviderRegistry {
    @Volatile
    private var registered: DreamProvider? = null

    /** 注册梦境提供者 (后注册者胜 — 第三方插件可覆盖内置默认)。 */
    @Synchronized
    fun register(provider: DreamProvider) { registered = provider }

    @Synchronized
    fun unregister(providerName: String) {
        if (registered?.providerName == providerName) registered = null
    }

    /** 当前生效的梦境提供者 (无注册 → 内核默认 DreamEngine)。 */
    fun active(): DreamProvider = registered ?: DreamEngine
}
