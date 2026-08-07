// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.KernelLog

/**
 * 不可信数据管道 — 提示词注入软硬结合防护的核心 (P0 定案 v0.34.0)。
 *
 * ## 信任边界
 * 系统提示词 (框架控制) > 用户本地输入 (设备主人) > 远程消息 (ACP GUEST 另有命令级防火墙) >
 * **工具结果/网页/文件/搜索 (外部数据, 不可信)**。
 * LLM 无法从机制上区分「阅读内容」与「执行指令」— 注入 = 在低信任文本里伪装高信任指令。
 *
 * ## 硬层 (机制级, 不依赖模型判断)
 * - [stripInjection]: 不可信文本进上下文前, 命中 InjectionPatterns 的片段直接**剥离**
 *   (数据层不允许指令形态文本存在, 而非加防御前缀 — 前缀注入对话流本身就是注入面)。
 * - [wrap]: 不可信文本进上下文时包裹 `<untrusted_data>` 标记, 系统提示词一次性声明
 *   标记内内容仅阅读不执行。
 *
 * ## 软层 (模型级)
 * - 系统提示词「信任边界」小节: 语义级注入 (伪装身份/渐进诱导) 靠行为准则兜底。
 * - 命中**静默**处理: 仅日志, 不反射检测细节 (防攻击者观察前缀后反向利用)。
 */
object UntrustedContent {
    const val OPEN_TAG = "<untrusted_data>"
    const val CLOSE_TAG = "</untrusted_data>"

    /**
     * 剥离文本中命中的指令形态片段 (中英注入模式, 单一事实源 InjectionPatterns)。
     * 宁可断句不可留指令形态 — 用于工具结果/远程任务等不可信数据。
     * 干净文本原样返回。
     */
    fun stripInjection(text: String): String {
        var r = text
        InjectionPatterns.INJECTION_PATTERNS.forEach { r = it.replace(r, "") }
        return r
    }

    /** 包装为不可信数据标记 (LLM 上下文用; 系统提示词声明标记内仅阅读不执行)。 */
    fun wrap(text: String): String = "$OPEN_TAG\n$text\n$CLOSE_TAG"

    /** 进 LLM 上下文前的完整处理: 剥离指令形态 → 包裹标记。 */
    fun forModel(text: String): String = wrap(stripInjection(text))

    /**
     * Agent 任务入口净化 (本地 run / 远程委托 inbox 任务统一走此):
     * 命中精确注入模式 → 静默剥离 + 日志, 不注入任何防御文本 (防御机制对攻击者不可见)。
     */
    fun sanitizeForAgent(task: String): String {
        val cleaned = stripInjection(task)
        if (cleaned != task) {
            KernelLog.w("UntrustedContent", "Task matched injection pattern — silently stripped (${task.length - cleaned.length} chars)")
        }
        return cleaned
    }
}
