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

/** 流式文本中的完整工具调用行 (多行锚定, 行尾须完整) — 半截工具名不匹配, 避免误报. */
internal val ACTION_LINE_REGEX = Regex("""(?m)^Action:\s*([\w.+\-]+)\s*$""")

/**
 * 运行中步骤气泡的跨线程索引+身份守卫 (P2 修复: 原局部 var 被主协程 / 引擎回调线程
 * (onDelta/onStep) / 播放协程(Default) 三方读写, 无可见性保证 — @Volatile 立即可见)。
 */
internal class RunningStepTracker {
    @Volatile var index: Int = -1
    @Volatile var ref: ChatMessageUi.AgentStep? = null
}
