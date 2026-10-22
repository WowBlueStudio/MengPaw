// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.DataPaths

/**
 * 长期记忆之后的注入段 (P3, docs/memory-git-sync-design.md §8):
 * 1. `memory/index.md` 摘要 — ≤200 token, 全文 `cat` 按需取;
 * 2. `## 你的设备与能力` 多端清单 — P4, 见 [PromptDeviceSection] (无库存文件时不注入);
 * 3. 冲突标记裁决常驻指引 — 孪生合并可能在工作区文件留下冲突块。
 *
 * 自 [PromptSystemBuilder] 拆出 (400 行文件限制), 段序仍由调用方保证:
 * 长期记忆 → 记忆索引 → 设备清单 → 常驻指引 → pinned 技能。
 */
internal object PromptMemorySection {

    /** 索引注入预算 — 约 320 中文字符 (≈200 token)。 */
    private const val INDEX_INJECT_CHARS = 320

    fun block(lang: PromptEngine.AgentLanguage, agentName: String, indexDoc: String): String {
        val summary = indexDoc.trim().take(INDEX_INJECT_CHARS)
        return buildString {
            if (summary.isNotBlank()) {
                append(
                    if (lang == PromptEngine.AgentLanguage.CHINESE)
                        "\n\n## 你的记忆索引（自动生成，勿手改）\n\n"
                    else
                        "\n\n## Your Memory Index (auto-generated, do not edit)\n\n"
                )
                append(summary)
                append("\n\n完整内容: cat ${DataPaths.AGENTS}/$agentName/memory/index.md")
            }
            // ── P4: 设备与能力 (并列入注入段; 无 devices/index.md 时为空串) ──
            append(PromptDeviceSection.block(lang))
            append(
                if (lang == PromptEngine.AgentLanguage.CHINESE)
                    "\n\n- 文件里出现冲突标记（<<<<<<< / ======= / >>>>>>>）时，先裁决冲突再执行任务。\n"
                else
                    "\n\n- When a file contains conflict markers (<<<<<<< / ======= / >>>>>>>), resolve them before executing.\n"
            )
        }
    }
}
