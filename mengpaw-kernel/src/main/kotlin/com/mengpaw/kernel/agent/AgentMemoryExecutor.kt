// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.io.File

/**
 * Built-in agent.memory.* CLI commands — 记忆三轨管理 (长期/中期/项目)。
 *
 * 从 AgentExecutor.kt 拆出 (2026-08-01, ≥50KB 文件拆分): 本类 18 条 memory 系命令,
 * 只依赖 AgentDocs/DataPaths, 无 UI/文档依赖; AgentExecutor 经
 * `val commands = 通用命令 + memoryExecutor.commands` 合并注册, 命名空间不变 (agent.*)。
 */
class AgentMemoryExecutor {

    /** Resolve the effective agent name, falling back to default. */
    private fun agentName(ctx: ExecutionContext) = ctx.agentName ?: "agent"

    /**
     * 火种模式 (scope="swarm") 的 worker 是零待命临时执行体——
     * 禁止写记忆, 防止并行 worker 向 Agent 三轨记忆注入噪音。
     */
    private fun swarmWriteBlocked(ctx: ExecutionContext): Boolean = ctx.scope == "swarm"

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "memory" to ::memory,
        "memory.record" to ::memoryRecord,
        "memory.keep" to ::memoryKeep,
        "memory.read" to ::memoryRead,
        "memory.search" to ::memorySearch,
        "memory.stats" to ::memoryStats,
        "memory.write" to ::memoryWrite,
        "memory.mid" to ::memoryMid,
        "memory.project" to ::memoryProject,
        "memory.project.save" to ::memoryProjectSave,
        "memory.project.delete" to ::memoryProjectDelete,
        "memory.mid.delete" to ::memoryMidDelete,
        "memory.rm" to ::memoryRm,
        "memory.edit" to ::memoryEdit,
        "memory.mid.rm" to ::memoryMidRm,
        "memory.mid.edit" to ::memoryMidEdit,
        "memory.project.rm" to ::memoryProjectRm,
        "memory.project.edit" to ::memoryProjectEdit
    )

    private suspend fun memory(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // Show LONG-TERM memory — what's in the system prompt
        val ltm = AgentDocs.readLongTermMemory(agentName(ctx))
        if (ltm.isBlank()) return ExecutionResult.ok(buildString {
            appendLine("(长期记忆为空)")
            appendLine()
            appendLine("三种添加方式:")
            appendLine("1. 用户说「请你记住...」 → Agent 执行 agent.memory.keep <内容>")
            appendLine("2. Agent 自主判断重要 → agent.memory.keep <内容>")
            appendLine("3. agent.dream 梦境整理 → 从中期记忆提炼")
            appendLine()
            appendLine("中期记忆: agent.memory.mid — 查看按日期分片的对话记录")
        })
        val lineCount = ltm.lines().count { it.startsWith("## ") }
        return ExecutionResult.ok(buildString {
            appendLine("## 长期记忆 ($lineCount 条, 已注入系统提示词)")
            appendLine()
            append(ltm.take(2000))
            if (ltm.length > 2000) appendLine("\n... (截断, 共 ${ltm.length} 字符)")
        })
    }

    private suspend fun memoryRecord(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (swarmWriteBlocked(ctx)) return ExecutionResult.ok("⛔ 火种模式 worker 不写记忆 (零待命临时执行体, 结果由协调器汇总)")
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.memory.record <内容>\n记录到中期记忆 (按日分片), 不会注入系统提示词。用 agent.memory.keep 升级到长期记忆。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val content = args.joinToString(" ")
        AgentDocs.appendMidTermMemory(agentName(ctx), content)
        return ExecutionResult.ok(buildString {
            appendLine("已记录到中期记忆 (今日分片)")
            appendLine("提示: 如果这是重要的/可复用的经验, 用 agent.memory.keep 升级到长期记忆")
        })
    }

    /** Promote important info from mid-term to long-term memory. */
    private suspend fun memoryKeep(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (swarmWriteBlocked(ctx)) return ExecutionResult.ok("⛔ 火种模式 worker 不写记忆 (零待命临时执行体, 结果由协调器汇总)")
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.memory.keep <内容>\n将重要信息写入长期记忆 (注入系统提示词)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val content = args.joinToString(" ")
        AgentDocs.appendLongTermMemory(agentName(ctx), content)
        return ExecutionResult.ok(buildString {
            appendLine("已写入长期记忆 ✅")
            appendLine("此内容将在下次对话中出现在系统提示词中")
            appendLine()
            appendLine("当前长期记忆总数: ${AgentDocs.readLongTermMemory(agentName(ctx)).lines().count { it.startsWith("## ") }} 条")
        })
    }

    /** Read a single memory entry by ID across all three tracks (long/mid/project). */
    private suspend fun memoryRead(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val id = args.joinToString(" ").trim()
        if (id.isEmpty()) return ExecutionResult.fail(buildString {
            appendLine("用法: agent.memory.read <id>")
            appendLine("按 ID 读取一条记忆 (跨长期/中期/项目三轨)。")
            appendLine("条目 ID 是时间戳, 先 agent.memory / agent.memory.mid / agent.memory.project 查看。")
        })
        val agent = agentName(ctx)
        val tracks = buildList {
            add(DataPaths.longTermMemoryFile(agent) to "长期记忆")
            AgentDocs.listMidTermDates(agent).forEach { add(DataPaths.midTermMemoryFile(agent, it) to "中期记忆 · $it") }
            DataPaths.projectMemoryFiles(agent).forEach { add(DataPaths.projectMemoryFile(agent, it) to "项目记忆 · $it") }
        }
        for ((path, label) in tracks) {
            val count = AgentDocs.countMatchingEntries(path, id)
            if (count > 1) return ExecutionResult.fail(
                "匹配到 $count 条, ID 不够精确。请用完整时间戳 (含时分), 如 \"2026-07-25 14:30\"。")
            if (count == 1) {
                val text = File(path).readText()
                val entry = text.split(Regex("(?=## )")).filter { it.trimStart().startsWith("## $id") }.first()
                return ExecutionResult.ok("## $label\n\n${entry.trim()}")
            }
        }
        return ExecutionResult.fail(
            "未找到条目: $id\n提示: 条目 ID 是时间戳 (如 \"2026-07-25 14:30\"), 用 agent.memory / agent.memory.mid / agent.memory.project 查看。")
    }

    /** Search memory across tracks by keywords. Usage: agent.memory.search <query> [--track long|mid|project] */
    private suspend fun memorySearch(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val trackIdx = args.indexOf("--track")
        val query = (if (trackIdx >= 0) args.take(trackIdx) else args).joinToString(" ").trim()
        if (query.isEmpty()) return ExecutionResult.fail(
            "用法: agent.memory.search <关键词> [--track long|mid|project]\n默认跨三轨搜索 (长期/中期/项目记忆)。")
        val keywords = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        val track = if (trackIdx >= 0 && trackIdx + 1 < args.size) args[trackIdx + 1] else "all"
        val agent = agentName(ctx)
        return when (track) {
            "long" -> ExecutionResult.ok(AgentDocs.searchLongTermMemory(agent, keywords).ifBlank { "(长期记忆无匹配)" })
            "mid" -> ExecutionResult.ok(AgentDocs.searchMidTermMemory(agent, keywords).ifBlank { "(中期记忆无匹配)" })
            "project" -> ExecutionResult.ok(AgentDocs.searchProjectMemory(agent, keywords).ifBlank { "(项目记忆无匹配)" })
            else -> {
                val sections = listOf(
                    AgentDocs.searchLongTermMemory(agent, keywords),
                    AgentDocs.searchMidTermMemory(agent, keywords),
                    AgentDocs.searchProjectMemory(agent, keywords)
                ).filter { it.isNotBlank() }
                if (sections.isEmpty()) ExecutionResult.ok("(三轨记忆均无匹配: $query)")
                else ExecutionResult.ok(sections.joinToString("\n\n") { it.trim() })
            }
        }
    }

    /** Memory statistics across all three tracks. */
    private suspend fun memoryStats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val longCount = AgentDocs.readLongTermMemory(agent).lines().count { it.startsWith("## ") }
        val mid = AgentDocs.midTermStats(agent)
        val midCount = mid.values.sum()
        val projects = DataPaths.projectMemoryFiles(agent)
        return ExecutionResult.ok(buildString {
            appendLine("## 记忆统计")
            appendLine("- 长期记忆: $longCount 条 (注入系统提示词)")
            appendLine("- 中期记忆: $midCount 条 (${mid.size} 个日期分片)")
            mid.entries.sortedByDescending { it.key }.take(5).forEach { (d, c) ->
                appendLine("    $d: $c 条")
            }
            appendLine("- 项目记忆: ${projects.size} 个项目")
            projects.take(10).forEach { appendLine("    $it") }
        })
    }

    /** Write a long-term memory entry with a specified ID (updates if exists). */
    private suspend fun memoryWrite(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (swarmWriteBlocked(ctx)) return ExecutionResult.ok("⛔ 火种模式 worker 不写记忆 (零待命临时执行体, 结果由协调器汇总)")
        if (args.size < 2) return ExecutionResult.fail(buildString {
            appendLine("用法: agent.memory.write <id> <内容>")
            appendLine("按指定 ID 写一条长期记忆 (已存在则更新)。ID 建议用时间戳或短标题。")
        })
        val id = args[0].trim()
        val content = args.drop(1).joinToString(" ").trim()
        if (content.isEmpty()) return ExecutionResult.fail("内容不能为空。用法: agent.memory.write <id> <内容>")
        val agent = agentName(ctx)
        val ltf = DataPaths.longTermMemoryFile(agent)
        val count = AgentDocs.countMatchingEntries(ltf, id)
        return when {
            count > 1 -> ExecutionResult.fail("匹配到 $count 条, ID 不够精确, 无法更新。请用完整时间戳。")
            count == 1 -> {
                if (AgentDocs.editLongTermEntry(agent, id, content) == 1)
                    ExecutionResult.ok("已更新长期记忆条目: $id ✅")
                else ExecutionResult.fail("更新失败 (写入错误)")
            }
            else -> {
                AgentDocs.appendLongTermMemory(agent, content, id)
                ExecutionResult.ok("已写入长期记忆: $id ✅\n此内容将在下次对话中出现在系统提示词中")
            }
        }
    }

    /** Show mid-term memory — dated files, not in prompt. */
    private suspend fun memoryMid(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return if (args.isEmpty()) {
            // Show available date files
            val dates = AgentDocs.listMidTermDates(agentName(ctx))
            if (dates.isEmpty()) return ExecutionResult.ok("(无中期记忆)")
            val stats = AgentDocs.midTermStats(agentName(ctx))
            ExecutionResult.ok(buildString {
                appendLine("## 中期记忆分片 (按日期)")
                appendLine()
                stats.forEach { (date, count) ->
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val marker = if (date == today) " ← 今天" else ""
                    appendLine("- $date: $count 条$marker")
                }
                appendLine()
                appendLine("读取: agent.memory.mid <date>")
            })
        } else {
            val date = args[0]
            val content = AgentDocs.readMidTermMemoryDate(agentName(ctx), date)
            if (content.isBlank()) return ExecutionResult.ok("(该日期无中期记忆: $date)")
            val lineCount = content.lines().count { it.startsWith("## ") }
            ExecutionResult.ok(buildString {
                appendLine("## 中期记忆 · $date ($lineCount 条)")
                appendLine()
                append(content.take(3000))
                if (content.length > 3000) appendLine("\n... (截断, 共 ${content.length} 字符)")
            })
        }
    }

    /** Project memory — list all project memories or read one. */
    private suspend fun memoryProject(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        return if (args.isEmpty()) {
            val projects = DataPaths.projectMemoryFiles(agent)
            if (projects.isEmpty()) return ExecutionResult.ok(buildString {
                appendLine("(无项目记忆)")
                appendLine()
                appendLine("创建项目记忆:")
                appendLine("  agent.memory.project.save <项目名> <总结内容>")
                appendLine()
                appendLine("在项目里程碑完成或闭环后总结经验, 形成可复用的项目模式。")
            })
            ExecutionResult.ok(buildString {
                appendLine("## 项目记忆 (${projects.size} 个项目)")
                appendLine()
                projects.forEach { name ->
                    val file = File(DataPaths.projectMemoryFile(agent, name))
                    val firstLine = try { file.readLines().firstOrNull { it.startsWith("> 创建:") } ?: "" } catch (_: Exception) { "" }
                    appendLine("- **$name** $firstLine")
                }
                appendLine()
                appendLine("读取: agent.memory.project <项目名>")
            })
        } else {
            val projectName = args[0]
            val content = AgentDocs.readProjectMemory(agent, projectName)
            if (content.isBlank()) return ExecutionResult.ok("(项目记忆不存在: $projectName)")
            ExecutionResult.ok(content)
        }
    }

    /** Save a project milestone/closing report. */
    private suspend fun memoryProjectSave(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (swarmWriteBlocked(ctx)) return ExecutionResult.ok("⛔ 火种模式 worker 不写记忆 (零待命临时执行体, 结果由协调器汇总)")
        if (args.size < 2) return ExecutionResult.fail(
            "用法: agent.memory.project.save <项目名> <总结内容>\n" +
            "在项目里程碑或闭环后总结经验, 形成可复用的项目完成模式。"
        )
        val projectName = args[0]
        val report = args.drop(1).joinToString(" ")
        val agent = agentName(ctx)
        AgentDocs.saveProjectMemory(agent, projectName, report)
        return ExecutionResult.ok(buildString {
            appendLine("项目记忆已保存: $projectName ✅")
            appendLine()
            appendLine("此经验将在后续类似项目中可复用。")
            appendLine("查看: agent.memory.project $projectName")
            appendLine("列表: agent.memory.project")
        })
    }

    /** Edit a single long-term memory entry. */
    private suspend fun memoryEdit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: agent.memory.edit <时间戳> <新内容>\n修改长期记忆中的某一条。先用 agent.memory 查看，复制时间戳。"
        )
        val entryId = args[0]
        val newContent = args.drop(1).joinToString(" ")
        if (entryId.length < 10) return ExecutionResult.fail("时间戳太短，需要至少 10 字符的完整时间戳。")
        val agent = agentName(ctx)
        val edited = AgentDocs.editLongTermEntry(agent, entryId, newContent)
        return when (edited) {
            1 -> ExecutionResult.ok("已修改: $entryId")
            0 -> ExecutionResult.fail(singleEntryFail(agent, DataPaths.longTermMemoryFile(agent), entryId, "长期记忆"))
            else -> ExecutionResult.fail("内部错误")
        }
    }

    /** Delete a single entry from a mid-term date file. */
    private suspend fun memoryMidRm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: agent.memory.mid.rm <date> <时间戳>\n从指定日期的中期记忆分片中删除一条。先用 agent.memory.mid <date> 查看。"
        )
        val date = args[0]
        val entryId = args.drop(1).joinToString(" ")
        if (entryId.length < 6) return ExecutionResult.fail("时间戳太短。中期记忆条目以 HH:mm:ss 开头，如 \"14:30:15\"。")
        val agent = agentName(ctx)
        val path = DataPaths.midTermMemoryFile(agent, date)
        val deleted = AgentDocs.deleteEntry(agent, path, entryId)
        return when (deleted) {
            1 -> ExecutionResult.ok("已从中期记忆 ($date) 删除: $entryId")
            0 -> ExecutionResult.fail(singleEntryFail(agent, path, entryId, "中期记忆 $date"))
            else -> ExecutionResult.fail("内部错误")
        }
    }

    /** Edit a single entry in a mid-term date file. */
    private suspend fun memoryMidEdit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 3) return ExecutionResult.fail(
            "用法: agent.memory.mid.edit <date> <时间戳> <新内容>\n修改中期记忆中的某一条。先用 agent.memory.mid <date> 查看。"
        )
        val date = args[0]
        val entryId = args[1]
        val newContent = args.drop(2).joinToString(" ")
        if (entryId.length < 6) return ExecutionResult.fail("时间戳太短。")
        val agent = agentName(ctx)
        val path = DataPaths.midTermMemoryFile(agent, date)
        val edited = AgentDocs.editEntry(agent, path, entryId, newContent)
        return when (edited) {
            1 -> ExecutionResult.ok("已修改中期记忆 ($date): $entryId")
            0 -> ExecutionResult.fail(singleEntryFail(agent, path, entryId, "中期记忆 $date"))
            else -> ExecutionResult.fail("内部错误")
        }
    }

    /** Delete a single entry from a project memory file. */
    private suspend fun memoryProjectRm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: agent.memory.project.rm <项目名> <时间戳>\n从项目记忆中删除一条。先用 agent.memory.project <项目名> 查看。"
        )
        val projectName = args[0]
        val entryId = args.drop(1).joinToString(" ")
        if (entryId.length < 10) return ExecutionResult.fail("时间戳太短，需要至少 10 字符。")
        val agent = agentName(ctx)
        val path = DataPaths.projectMemoryFile(agent, projectName)
        val deleted = AgentDocs.deleteEntry(agent, path, entryId)
        return when (deleted) {
            1 -> ExecutionResult.ok("已从项目记忆 ($projectName) 删除: $entryId")
            0 -> ExecutionResult.fail(singleEntryFail(agent, path, entryId, "项目记忆 $projectName"))
            else -> ExecutionResult.fail("内部错误")
        }
    }

    /** Edit a single entry in a project memory file. */
    private suspend fun memoryProjectEdit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 3) return ExecutionResult.fail(
            "用法: agent.memory.project.edit <项目名> <时间戳> <新内容>\n修改项目记忆中的某一条。先用 agent.memory.project <项目名> 查看。"
        )
        val projectName = args[0]
        val entryId = args[1]
        val newContent = args.drop(2).joinToString(" ")
        if (entryId.length < 10) return ExecutionResult.fail("时间戳太短。")
        val agent = agentName(ctx)
        val path = DataPaths.projectMemoryFile(agent, projectName)
        val edited = AgentDocs.editEntry(agent, path, entryId, newContent)
        return when (edited) {
            1 -> ExecutionResult.ok("已修改项目记忆 ($projectName): $entryId")
            0 -> ExecutionResult.fail(singleEntryFail(agent, path, entryId, "项目记忆 $projectName"))
            else -> ExecutionResult.fail("内部错误")
        }
    }

    /** Build a precise failure message for single-entry operations. */
    private fun singleEntryFail(agent: String, path: String, entryId: String, label: String): String {
        val count = AgentDocs.countMatchingEntries(path, entryId)
        return when {
            count > 1 -> "匹配到 $count 条记录, 时间戳不够精确。请用更完整的时间戳。"
            count == 0 -> "未找到匹配条目: $entryId。先查看 $label 获取完整时间戳。"
            else -> "操作失败。请重试。"
        }
    }

    /** Delete a mid-term memory date file. */
    private suspend fun memoryMidDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "用法: agent.memory.mid.delete <date>\n删除指定日期的中期记忆分片。用 agent.memory.mid 查看可用日期。"
        )
        val date = args[0]
        val agent = agentName(ctx)
        val ok = AgentDocs.deleteMidTermFile(agent, date)
        return if (ok) ExecutionResult.ok("已删除中期记忆分片: $date")
        else ExecutionResult.fail("分片不存在: $date。用 agent.memory.mid 查看可用日期。")
    }

    /** Delete a project memory file. */
    private suspend fun memoryProjectDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "用法: agent.memory.project.delete <项目名>\n删除指定项目的记忆。用 agent.memory.project 查看所有项目。"
        )
        val projectName = args[0]
        val agent = agentName(ctx)
        val ok = AgentDocs.deleteProjectMemory(agent, projectName)
        return if (ok) ExecutionResult.ok("已删除项目记忆: $projectName")
        else ExecutionResult.fail("项目记忆不存在: $projectName。用 agent.memory.project 查看所有项目。")
    }

    /** Remove a SINGLE entry from long-term memory by exact timestamp. */
    private suspend fun memoryRm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(buildString {
            appendLine("用法: agent.memory.rm <时间戳>")
            appendLine()
            appendLine("一次只删一条长期记忆。先用 agent.memory 查看所有条目，复制要删的条目的完整时间戳。")
            appendLine("示例: agent.memory.rm \"2026-07-25 14:30\"")
            appendLine()
            appendLine("限制:")
            appendLine("- 时间戳必须至少 10 个字符 (如 2026-07-25)")
            appendLine("- 一次只能删一条，不能批量删除")
            appendLine("- 不能删除整个长期记忆文件")
            appendLine("- 此操作不可逆")
        })
        val entryId = args.joinToString(" ")
        if (entryId.length < 10) return ExecutionResult.fail(
            "时间戳太短 (${entryId.length} 字符)，需要完整时间戳（至少 10 字符，如 \"2026-07-25\"）。先用 agent.memory 查看。"
        )
        val agent = agentName(ctx)
        val deleted = AgentDocs.deleteLongTermEntry(agent, entryId)
        return when (deleted) {
            1 -> ExecutionResult.ok("已删除: $entryId\n下次系统提示词不再包含此条目。")
            0 -> {
                // Check if too many matches
                val ltm = AgentDocs.readLongTermMemory(agent)
                val count = ltm.split(Regex("(?=## )")).count {
                    it.trimStart().startsWith("## $entryId")
                }
                if (count > 1) ExecutionResult.fail(
                    "匹配到 $count 条记录, 时间戳不够精确。请用更完整的时间戳（含时分），如 \"2026-07-25 14:30\"。"
                )
                else ExecutionResult.fail(
                    "未找到匹配条目: $entryId。先用 agent.memory 查看所有条目及其完整时间戳。"
                )
            }
            else -> ExecutionResult.fail("内部错误")
        }
    }
}
