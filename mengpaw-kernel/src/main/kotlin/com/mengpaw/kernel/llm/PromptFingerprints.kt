// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

/**
 * 提示词缓存指纹 (自 [PromptSystemBuilder] 拆出, 400 行文件限制)。
 * 任一指纹失配即重建系统提示词 — 覆盖 pinned 技能 / 触发器 / 进化数据三类易漏变化。
 */
internal object PromptFingerprints {

    /** pinned 技能指纹 — `.pinned` mtime + 每个 pinned 技能文件 mtime 拼接。 */
    fun pinned(): String {
        val pinned = com.mengpaw.kernel.skill.PinnedSkills.list()
        if (pinned.isEmpty()) return ".pinned:0"
        val sb = StringBuilder()
        sb.append(".pinned:").append(java.io.File(com.mengpaw.kernel.DataPaths.SKILLS, ".pinned").lastModified())
        for (name in pinned) {
            val f = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS, "$name.md")
            sb.append('|').append(name).append(':').append(if (f.exists()) f.lastModified() else -1L)
        }
        return sb.toString()
    }

    /** 触发器指纹 — 全部触发器的 (id:type:enabled) 拼接, 增删/启停即变化。 */
    fun trigger(): String =
        com.mengpaw.kernel.trigger.TriggerEngine.list()
            .joinToString("|") { "${it.id}:${it.type}:${it.enabled}" }

    /** 进化数据指纹: failures.jsonl + commands.json 的 (大小:修改时间) — 任一写入/清理即变化。 */
    fun evolution(agentName: String): String {
        return try {
            fun fp(f: java.io.File): String = if (f.exists()) "${f.length()}:${f.lastModified()}" else "-"
            val failures = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile(agentName))
            val commands = java.io.File(com.mengpaw.kernel.DataPaths.evolutionCommandsFile())
            "${fp(failures)}|${fp(commands)}"
        } catch (_: Exception) { "-" }
    }
}
