// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

/**
 * 命令参数歧义防护 (v0.34.3 全量审计) — 供所有"关键标识符/路径拼接"命令复用。
 *
 * 问题: Agent 常把描述性文本 ("等待结果"/"看看") 拼进路径/URL/时间戳参数尾部,
 * 全拼型命令 (joinToString) 会把污染文本并入关键参数 → 解析失败且原样复制重试;
 * 单 token 位置参数命令则静默忽略多余 token, Agent 不知情。
 */
object ParamGuard {

    /** 描述性文本词表 — 命中 = 高置信参数污染。 */
    val DESCRIPTION_WORDS: Set<String> = setOf(
        "等待结果", "结果", "看看", "查看", "输出", "等待", "然后", "谢谢", "好的",
        "完毕", "完成", "尝试", "试一下", "请", "ok", "OK", "thanks", "please", "wait", "done"
    )

    /** 全拼型污染提示: 末片段命中描述词表 → 附纯净重发指引。
     *  @param command 命令名 (重发格式); @return 附加文本或 null。 */
    fun pollutedHint(args: List<String>, command: String): String? {
        if (args.size < 2) return null
        val last = args.last()
        val isDescription = last in DESCRIPTION_WORDS ||
            DESCRIPTION_WORDS.any { last.startsWith(it) && last.length <= it.length + 2 }
        if (!isDescription) return null
        val clean = args.dropLast(1).joinToString(" ")
        return "\n⚠️ 参数污染提示: 「$last」疑似多余的描述文本被并入了参数 (收到 ${args.size} 个片段: ${args.joinToString(" + ")})。" +
            "路径/标识符参数只能是一个完整参数。请重发纯净参数: $command $clean"
    }

    /** 多余参数提示: 位置参数命令收到超过 [expected] 个参数时, 提示多余片段被忽略。
     *  防"静默忽略" — Agent 不知道污染参数没生效。 */
    fun extraArgsHint(args: List<String>, expected: Int, command: String): String? {
        if (args.size <= expected) return null
        val extra = args.drop(expected).joinToString(" + ")
        return "\n⚠️ 参数提示: 收到 ${args.size} 个参数 (期望 $expected 个), 多余的「$extra」已被忽略。" +
            "路径/URL 等标识符参数应是单个参数, 描述文本请放在 Thought 或后续对话, 不要并入参数。"
    }
}
