// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.ChatMessageUi

/**
 * 工具执行中提示的前缀 (v0.29.2, Reasonix ③ 对标 — 工具调用提前通知):
 * AgentViewModel 流式检测到完整 "Action: <tool>" 行后, 用该前缀推送运行中气泡;
 * ChatBubbles.WaitingIndicator 依此前缀显示"正在执行 X… Ns"而非"思考中… Ns"。
 */
internal const val EXECUTING_TOOL_PREFIX = "正在执行 "

/** 流式文本中的完整工具调用行 (多行锚定, 行尾必须 \n 落地) — 半截工具名不匹配, 避免误报.
 *  v0.37.3 修复: 原 `$` 在多行模式下也匹配"字符串末尾" — 模型逐字流式输出
 *  "Action: agent" 未写完 (无换行) 就被判为完整行, 每来一个字符误报一次,
 *  工具行逐字展开 (agent → agent.m → agent.memory…) 且调用次数虚高 (47 vs 12)。 */
internal val ACTION_LINE_REGEX = Regex("""(?m)^Action:\s*([\w.+\-]+)\s*\n""")

/**
 * 运行中步骤气泡的跨线程索引+身份守卫 (P2 修复: 原局部 var 被主协程 / 引擎回调线程
 * (onDelta/onStep) / 播放协程(Default) 三方读写, 无可见性保证 — @Volatile 立即可见)。
 */
internal class RunningStepTracker {
    @Volatile var index: Int = -1
    // v0.34.3 气泡 UI 重构: ref 泛化为任意运行中消息 (ThinkingProcess / FinalAnswer)
    @Volatile var ref: ChatMessageUi? = null
}
