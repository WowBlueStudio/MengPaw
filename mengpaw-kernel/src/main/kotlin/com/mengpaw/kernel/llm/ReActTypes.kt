// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

/**
 * Parsed ReAct response from LLM output.
 */
data class ReActResponse(
    val thought: String,
    val action: ToolCall?,
    val isFinal: Boolean,
    /** Model output Thought but no Action — loop should inject a continue prompt. */
    val needsContinue: Boolean = false,
    /** Multiple tool calls from one LLM output (parallel execution). Empty when only [action] set. */
    val actions: List<ToolCall> = emptyList()
)

data class ToolCall(
    val name: String,
    val parameters: Map<String, String>
) {
    /**
     * JSON 双轨制门卫: 检测参数是否为 JSON 形态。
     * PromptEngine 的 tolerant JSON 解析成功时丢弃 key 只取值 — 单 key 碰巧兼容,
     * 多 key 会参数错位 ({"force":true,"id":"x"} → "true x"); 解析失败时 raw 兜底
     * 会把整个 JSON 串当参数。两种情况都应返回 PARAM_FORMAT_ERROR, 不执行命令。
     * @return 错误描述文本, 或 null (参数格式正常, 可执行)
     */
    fun paramFormatError(): String? {
        val raw = parameters["raw"]
        val looksLikeJson = raw != null && raw.trim().startsWith("{")
        val multiValueJson = raw == null && parameters.size > 1
        return when {
            looksLikeJson || multiValueJson ->
                "参数格式错误: 命令 '$name' 收到 JSON/多字段参数, 但命令期望 CLI 纯文本。" +
                "正确示例: $name <参数1> [参数2]。多字段 JSON 会因 key 被丢弃导致参数错位。"
            else -> null
        }
    }
}
