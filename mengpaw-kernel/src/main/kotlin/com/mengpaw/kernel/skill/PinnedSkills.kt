// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.skill

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import java.io.File

/**
 * 用户指定技能清单 — 设置页技能行的「@」按钮写入。
 *
 * 语义: 用户指定该技能 = 注入系统提示词「用户指定技能」指针段(名称+描述一行),
 * LLM 不再用 skill.ls 遍历寻找, 直接 skill.run <name> 读取全文。
 * 技能全文不注入 — 只给指针, 内容按需读取 (对齐反模式警告: 知识查询类文档不常驻)。
 *
 * 存储: {BASE}/技能剧本/.pinned — 每行一个技能名 (行格式, 与 SkillSeeds manifest
 * 同款零 JSON 依赖; 损坏按空清单处理, 安全方向)。
 * 独立于技能文件本身 — 不被 SkillSeeds 覆盖 (注入意图是用户状态, 不能进 frontmatter)。
 */
object PinnedSkills {

    private val pinnedFile: File get() = File(DataPaths.SKILLS, ".pinned")

    /** 读取清单 — 损坏/缺失返回空列表。 */
    fun list(): List<String> = try {
        if (!pinnedFile.exists()) emptyList()
        else pinnedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (e: Exception) {
        KernelLog.w("PinnedSkills", "read failed: ${e.message}")
        emptyList()
    }

    fun isPinned(name: String): Boolean = list().contains(name)

    /** 切换指定状态 — 原子写 (tmp + rename), 返回切换后的状态。 */
    fun toggle(name: String): Boolean {
        if (name.isBlank() || name.contains("/") || name.contains("\\")) return false
        val current = list().toMutableList()
        val pinned = !current.contains(name)
        if (pinned) current.add(name) else current.remove(name)
        return try {
            pinnedFile.parentFile?.mkdirs()
            val tmp = File(pinnedFile.parentFile, ".pinned.tmp")
            tmp.writeText(current.joinToString("\n") + if (current.isEmpty()) "" else "\n")
            if (!tmp.renameTo(pinnedFile)) { pinnedFile.writeText(tmp.readText()); tmp.delete() }
            true
        } catch (e: Exception) {
            KernelLog.w("PinnedSkills", "toggle $name failed: ${e.message}")
            false
        }
    }

    /** 移除清单项 (技能删除时同步清理, 防悬空指针)。 */
    fun remove(name: String) {
        if (!isPinned(name)) return
        toggle(name)
    }
}
