// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.device.DeviceIndexWriter

/**
 * `## 你的设备与能力` 注入段 (P4, docs/memory-git-sync-design.md §8.1)。
 *
 * 数据源 = `{AGENTS}/twin/devices/index.md` (本机生成物, 见 [DeviceIndexWriter]):
 * 寄存设备一行 + 其余端每端一行 (≤8 端 ≈ 150 token), 不在工作区内, 故缓存失效靠**独立指纹**
 * ([PromptFingerprints.devices]), 不能靠 `AGENT_DOC_FILES` 的 mtime 快照。
 *
 * 段序: 长期记忆 → 记忆索引 → **设备清单** → 冲突裁决常驻指引 → pinned 技能
 * (末段追加, 不打断前缀区 —— 前插击穿前缀缓存)。
 */
internal object PromptDeviceSection {

    /** 其余端最多注入的行数 (§8.3: ≤8 端)。 */
    private const val MAX_OTHER_ROWS = 8

    fun block(lang: PromptEngine.AgentLanguage): String {
        val rows = DeviceIndexWriter.read().lineSequence()
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (rows.isEmpty()) return ""
        val selfRow = rows.firstOrNull { it.contains("· 本机") }?.replace(" · 本机", "")
        val others = rows.filter { !it.contains(" · 本机") }.take(MAX_OTHER_ROWS)

        return buildString {
            append('\n').append('\n')
            append(
                if (lang == PromptEngine.AgentLanguage.CHINESE) "## 你的设备与能力（多端清单）\n\n"
                else "## Your Devices & Capabilities (multi-end inventory)\n\n"
            )
            if (selfRow != null) {
                append(
                    if (lang == PromptEngine.AgentLanguage.CHINESE) "- 你寄存于：$selfRow\n"
                    else "- You are hosted on: $selfRow\n"
                )
            }
            if (others.isNotEmpty()) {
                append(
                    if (lang == PromptEngine.AgentLanguage.CHINESE) "- 其余 ${others.size} 端：\n"
                    else "- Other ${others.size} end(s):\n"
                )
                others.forEach { append("  - ").append(it).append('\n') }
            }
            append(
                if (lang == PromptEngine.AgentLanguage.CHINESE) {
                    "- 查详情：device.info <名称>；按能力找端：device.find <能力>\n" +
                        "- 你只登记自己那一行；其他端的环境由它们自己登记，不要替它们补写。\n" +
                        "- 文件里出现冲突标记（<<<<<<< / ======= / >>>>>>>）时，先裁决冲突再执行任务。\n"
                } else {
                    "- Details: device.info <name>; find by capability: device.find <capability>\n" +
                        "- You only register your own row; other ends register theirs — never fill them in.\n" +
                        "- When a file contains conflict markers (<<<<<<< / ======= / >>>>>>>), resolve them before executing.\n"
                }
            )
            append("\n完整内容: cat ${DataPaths.TWIN_DEVICES}/${DeviceIndexWriter.INDEX_NAME}")
        }
    }
}
