// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * agent.memory.* 写/改/删命令执行器 — record/keep/write/project.save/rm/edit/
 * mid.rm/mid.edit/project.rm/project.edit/mid.delete/project.delete
 * (拆自 AgentMemoryExecutor, 400 行文件拆分)。写命令统一经 [swarmWriteBlocked]
 * 屏蔽零待命并行 worker。
 */
internal class AgentMemoryMutateCommands {

    internal suspend fun memoryRecord(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryKeep(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (swarmWriteBlocked(ctx)) return ExecutionResult.ok("⛔ 火种模式 worker 不写记忆 (零待命临时执行体, 结果由协调器汇总)")
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.memory.keep <内容>\n将重要信息写入长期记忆 (注入系统提示词)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val content = args.joinToString(" ")
        AgentDocs.appendLongTermMemory(agentName(ctx), content)
        return ExecutionResult.ok(buildString {
            appendLine("已写入长期记忆 ✅")
            appendLine("此内容将在下次对话中出现在系统提示词中")
            appendLine()
            appendLine("当前长期记忆总数: ${AgentDocs.countLongTermEntries(agentName(ctx))} 条")
            // P0 (2026-08-08 自检): 回传内容预览 — 声称成功必须基于真实写入内容
            if (content.isNotBlank()) {
                appendLine()
                appendLine("本条内容预览 (前 200 字符):")
                appendLine(content.take(200))
                // 校验锚点 (P0 强化): 声称成功必须引用此片段中的真实文本
                val anchor = content.replace("\n", " ").trim().take(12)
                appendLine("[校验锚点] 内容开头: \"$anchor\"")
            }
        })
    }

    /** Write a long-term memory entry with a specified ID (updates if exists). */
    internal suspend fun memoryWrite(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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

    /** Save a project milestone/closing report. */
    internal suspend fun memoryProjectSave(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryEdit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryMidRm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: agent.memory.mid.rm <date> <时间戳>\n从指定日期的中期记忆分片中删除一条。先用 agent.memory.mid <date> 查看。"
        )
        val date = args[0]
        val entryId = args.drop(1).joinToString(" ")
        if (entryId.length < 6) return ExecutionResult.fail("时间戳太短。中期记忆条目以 HH:mm:ss 开头，如 \"14:30:15\"。")
        // v0.34.3 污染防护: 时间戳拼接型参数 — 描述文本会被并入 entryId 导致匹配失败
        com.mengpaw.kernel.cli.ParamGuard.pollutedHint(args.drop(1), "agent.memory.mid.rm")?.let {
            return ExecutionResult.fail(it, errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
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
    internal suspend fun memoryMidEdit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryProjectRm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: agent.memory.project.rm <项目名> <时间戳>\n从项目记忆中删除一条。先用 agent.memory.project <项目名> 查看。"
        )
        val projectName = args[0]
        val entryId = args.drop(1).joinToString(" ")
        if (entryId.length < 10) return ExecutionResult.fail("时间戳太短，需要至少 10 字符。")
        com.mengpaw.kernel.cli.ParamGuard.pollutedHint(args.drop(1), "agent.memory.project.rm")?.let {
            return ExecutionResult.fail(it, errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
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
    internal suspend fun memoryProjectEdit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryMidDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryProjectDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryRm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
