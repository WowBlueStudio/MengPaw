// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.security

/**
 * Shared injection detection patterns used by both [Sanitizer] and [PromptFirewall].
 *
 * Single source of truth — any update here is automatically picked up by both security
 * components, eliminating the previous duplication risk.
 */
object InjectionPatterns {

    /** Prompt injection / instruction override patterns. */
    val INJECTION_PATTERNS: List<Regex> = listOf(
        // Ignore/override instructions — English
        Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|earlier)\\s+(instructions|rules|prompts)"),
        // Ignore/override instructions — Chinese
        Regex("(?:忽略|忘掉|无视)\\s*(?:所有)?\\s*(?:之前|先前|上文)?\\s*(?:指令|指示|规则|提示|要求)"),
        // Unrestricted mode — English
        Regex("(?i)(unrestricted|debug|developer|admin|god|jailbreak)\\s+mode"),
        // Unrestricted mode — Chinese
        Regex("(?:无限制|越狱|开发者|调试|管理员|上帝)(?:模式)?"),
        // Bypass policy — English
        Regex("(?i)bypass\\s+(content|usage|safety)\\s+policy"),
        // Bypass policy — Chinese
        Regex("(?:绕过|躲开|规避)\\s*(?:内容|使用|安全)?\\s*(?:策略|政策|限制|审核)"),
        // Concealment — English
        Regex("(?i)do\\s+not\\s+(tell|inform|mention|notify)\\s+(the\\s+)?user"),
        // Concealment — Chinese
        Regex("(?:不要|勿|请勿|别)\\s*(?:告诉|告知|通知|提及)\\s*(?:用户|使用者)"),
        // Jailbreak variant (PromptFirewall specific check)
        Regex("(?i)(unrestricted|jailbreak|god\\s*mode)\\s*(mode|prompt)?")
    )

    /** Warning labels for matched injection patterns (same index as [INJECTION_PATTERNS]). */
    val INJECTION_LABELS: List<String> = listOf(
        "指令覆盖攻击",
        "指令覆盖攻击",
        "越狱模式请求",
        "越狱模式请求",
        "策略绕过请求",
        "策略绕过请求",
        "信息隐藏请求",
        "信息隐藏请求",
        "越狱模式请求"
    )

    /**
     * Check if the given text matches any injection pattern.
     * @return The warning label of the first matched pattern, or null if clean.
     */
    fun findMatch(text: String): String? {
        for (i in INJECTION_PATTERNS.indices) {
            if (INJECTION_PATTERNS[i].containsMatchIn(text)) {
                return INJECTION_LABELS[i]
            }
        }
        return null
    }
}
