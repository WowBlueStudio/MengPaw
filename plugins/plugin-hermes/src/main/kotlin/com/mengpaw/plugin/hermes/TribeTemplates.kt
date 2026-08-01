// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

/**
 * Tribe 任务模板 — 预置提示词骨架，支持 {content} 与自定义参数替换。
 *
 * 用法: `tribe.delegate <agent> --template <name> <content>`
 */
object TribeTemplates {

    data class Template(val name: String, val desc: String, val skeleton: String)

    val all = listOf(
        Template("summarize", "总结", "请总结以下内容，提取要点并分条列出，保留关键数字与结论：\n\n{content}"),
        Template("translate", "翻译", "请将以下内容翻译为{lang}（默认简体中文），保留原有格式：\n\n{content}"),
        Template("research", "调研", "请调研以下主题，输出：一、背景；二、关键事实（尽量给出证据/来源）；三、结论与建议：\n\n{content}"),
        Template("review", "代码审查", "请审查以下代码/方案，输出：问题清单（按严重程度排序）、修改建议、风险点：\n\n{content}"),
        Template("brainstorm", "头脑风暴", "针对以下主题给出 5-10 个可行方案，各附一句理由：\n\n{content}"),
        Template("draft", "起草", "请基于以下要点起草一份结构化文档，语言正式、逻辑连贯：\n\n{content}")
    )

    /** 渲染模板：{content} 替换为内容，{param} 替换为参数值。 */
    fun render(name: String, content: String, params: Map<String, String> = emptyMap()): String? {
        val t = all.find { it.name == name } ?: return null
        var result = t.skeleton.replace("{content}", content)
        params.forEach { (k, v) -> result = result.replace("{$k}", v) }
        return result
    }

    fun describe(): String = all.joinToString("\n") { "• `$it.name` — ${it.desc}" }
}
