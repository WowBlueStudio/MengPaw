// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/** 团队成员。 */
data class TeamMember(
    val id: String,
    val name: String,
    val role: String,
    val status: String,
    val skills: String
)

/**
 * 团队数据访问 — 从 Agent文档/team 目录的 md 文件读取成员列表。
 * 供自动组队 / 路由 / Fleet 共用。
 */
object TribeTeamStore {

    private val teamDir: File get() = File(DataPaths.TEAM).also { it.mkdirs() }

    /** 解析 team 目录下的所有成员，按名称排序。 */
    fun discoverMembers(): List<TeamMember> {
        return try {
            teamDir.listFiles()?.filter { it.extension == "md" }?.map { file ->
                val text = try { file.readText() } catch (e: Exception) { ErrorCollector.report(e, "TribeTeamStore.discoverMembers"); "" }
                TeamMember(
                    id = file.nameWithoutExtension,
                    name = Regex("name:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: file.nameWithoutExtension,
                    role = Regex("role:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "未设定",
                    status = Regex("status:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "active",
                    skills = Regex("skills:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "通用"
                )
            }?.sortedBy { it.name } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** 按名字或 id 查找成员。 */
    fun findMember(target: String): TeamMember? =
        discoverMembers().find { it.name == target || it.id == target }

    /** 邀请成员（写 team 目录 .md 文件）。 */
    fun invite(id: String, role: String): File {
        val file = File(teamDir, "$id.md")
        file.writeText("name: $id\nrole: $role\njoined: ${System.currentTimeMillis()}\nstatus: active")
        return file
    }

    /** 移除成员。 */
    fun remove(id: String): Boolean = File(teamDir, "$id.md").delete()
}
