// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import java.io.File

/**
 * agent.memory.* 只读命令执行器 — memory/memory.read/memory.search/memory.stats/
 * memory.mid/memory.project (拆自 AgentMemoryExecutor, 400 行文件拆分)。
 */
internal class AgentMemoryReadCommands {

    internal suspend fun memory(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
        val lineCount = AgentDocs.countLongTermEntries(agentName(ctx))
        return ExecutionResult.ok(buildString {
            appendLine("## 长期记忆 ($lineCount 条, 已注入系统提示词)")
            appendLine()
            append(ltm.take(2000))
            if (ltm.length > 2000) appendLine("\n... (截断, 共 ${ltm.length} 字符)")
        })
    }

    /** Read a single memory entry by ID across all three tracks (long/mid/project). */
    internal suspend fun memoryRead(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memorySearch(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryStats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val longCount = AgentDocs.countLongTermEntries(agent)
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

    /** Show mid-term memory — dated files, not in prompt. */
    internal suspend fun memoryMid(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun memoryProject(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
}
