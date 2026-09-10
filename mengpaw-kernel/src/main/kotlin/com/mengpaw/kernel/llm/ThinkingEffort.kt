// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

/**
 * 思考强度档位 (v0.46.2) — 官方依据 api-docs.deepseek.com/zh-cn/guides/thinking_mode:
 *
 * - 思考模式开关 (OpenAI 格式): `{"thinking": {"type": "enabled/disabled"}}`
 * - 思考强度控制 (OpenAI 格式): `{"reasoning_effort": "low/high/max"}`
 * - 官方原文: 「思考模式默认打开，且 effort 默认为 high」; 思考模式下
 *   `temperature`/`top_p` 等参数官方忽略 (传入不报错也不生效)。
 *
 * 四档语义: [MAX]/[HIGH]/[LOW] 显式开启思考并指定强度 (官方映射表: low→low,
 * medium/high/xhigh→high, max→max); [OFF] 走官方开关的 disabled, 不发送 `reasoning_effort`
 * (Anthropic 格式对应 `reasoning.effort = "none"`)。
 *
 * 为什么需要 OFF: 思考模式默认 high 时, 思维链与正文共享 `max_tokens` 输出预算, 且
 * 官方模型存在「思维链未终止 → 整段回答落进 `reasoning_content`, `content` 为空」的行为 —
 * 只读 `content` 的调用方 (本项目 ReAct 链) 会拿到空响应。ReAct/工具型任务用 [LOW] 或 [OFF]
 * 可显著降低该风险。
 *
 * 生效范围: 仅 DeepSeek 端点 (见 [effectiveThinkingEffort]) — 其它 OpenAI 兼容端点不接受
 * `thinking` / `reasoning_effort` 字段。
 */
enum class ThinkingEffort(val wire: String, val label: String, val thinkEnabled: Boolean) {
    MAX("max", "Max", true),
    HIGH("high", "High", true),
    LOW("low", "Low", true),
    OFF("", "Off", false);

    companion object {
        /** 官方默认档 (思考模式默认打开 + effort 默认 high) — 不指定时与厂商默认行为一致。 */
        val DEFAULT: ThinkingEffort = HIGH

        /** 从持久化字符串还原 (未知/空值回退 [DEFAULT], 兼容旧配置)。 */
        fun fromStorage(raw: String?): ThinkingEffort =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 实际注入思考档位的判定: 仅 DeepSeek 端点注入, 其它端点返回 null (不写进请求体)。
 * 官方文档仅 DeepSeek 记载 `thinking` / `reasoning_effort` (OpenAI 官方 chat/completions
 * 未记载该字段), 对其它厂商注入会 400 或被忽略。
 */
internal fun effectiveThinkingEffort(providerType: String, effort: ThinkingEffort): ThinkingEffort? =
    if (providerType == "deepseek") effort else null
