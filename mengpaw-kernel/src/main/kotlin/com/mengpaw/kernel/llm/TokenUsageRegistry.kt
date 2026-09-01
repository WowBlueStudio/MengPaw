// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

/**
 * Token 用量统一记录注册器 (v0.46.1) — 内核 LLM 调用完成时经此广播 usage 给宿主 (shell)。
 *
 * 根治方案: 不再由 shell 事后读共享 lastUsage (swarm/fleet 角色独立 provider / PLAN 不传 onStep /
 * GOAL rubric complete 覆盖 lastUsage 等场景读不到或串味), 而是 AdaptiveLlmProvider 每次调用完成
 * (流式 onUsage / 非流式 parseResponse) 直接回调 recorder。recorder 由宿主注入 (TokenStatsCollector.record),
 * 未注入时为空操作。
 */
object TokenUsageRegistry {
    /** (model, usage) → 宿主记录回调; shell 启动时注入。 */
    @Volatile var recorder: ((String, TokenUsage) -> Unit)? = null

    fun record(model: String, usage: TokenUsage) {
        try { recorder?.invoke(model, usage) } catch (_: Exception) { }
    }
}
