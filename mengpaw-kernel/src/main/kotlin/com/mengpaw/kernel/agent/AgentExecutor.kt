// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import kotlinx.serialization.json.*

/**
 * Built-in agent.* CLI commands — Agent document management.
 */
class AgentExecutor(private val docManager: AgentDocManager) {
    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "docs" to ::docs,
        "memory" to ::memory,
        "memory.record" to ::memoryRecord,
        "memory.keep" to ::memoryKeep,
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
        "memory.project.edit" to ::memoryProjectEdit,
        "cli" to ::cli,
        "boost" to ::boost,
        "boost.delete" to ::boostDelete,
        "profile" to ::profile,
        "soul" to ::soul,
        "audit" to ::audit,
        "browser-tools" to ::browserTools,
        "dream" to ::dream,
        "cleanup" to ::cleanup,
        "storage" to ::storageReport,
        "sessions" to ::sessions,
        "session.delete" to ::sessionDelete,
        "session.archive" to ::sessionArchive,
        "session.current" to ::sessionCurrent,
        "read" to ::readFile,
        "write" to ::writeFile,
        "ls" to ::listFiles,
        "rm" to ::deleteFile,
        "mkdir" to ::makeDir
    )

    private suspend fun docs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val docs = docManager.listDocs()
        return ExecutionResult.ok("Agent 文档 (${docs.size}):\n" + docs.joinToString("\n") { "  • $it" })
    }

    private suspend fun memory(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // Show LONG-TERM memory — what's in the system prompt
        val ltm = AgentDocs.readLongTermMemory(ctx.agentName ?: "MengPaw")
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
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.memory.record <内容>\n记录到中期记忆 (按日分片), 不会注入系统提示词。用 agent.memory.keep 升级到长期记忆。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val content = args.joinToString(" ")
        AgentDocs.appendMidTermMemory(ctx.agentName ?: "MengPaw", content)
        return ExecutionResult.ok(buildString {
            appendLine("已记录到中期记忆 (今日分片)")
            appendLine("提示: 如果这是重要的/可复用的经验, 用 agent.memory.keep 升级到长期记忆")
        })
    }

    /** Promote important info from mid-term to long-term memory. */
    private suspend fun memoryKeep(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.memory.keep <内容>\n将重要信息写入长期记忆 (注入系统提示词)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val content = args.joinToString(" ")
        AgentDocs.appendLongTermMemory(ctx.agentName ?: "MengPaw", content)
        return ExecutionResult.ok(buildString {
            appendLine("已写入长期记忆 ✅")
            appendLine("此内容将在下次对话中出现在系统提示词中")
            appendLine()
            appendLine("当前长期记忆总数: ${AgentDocs.readLongTermMemory(ctx.agentName ?: "MengPaw").lines().count { it.startsWith("## ") }} 条")
        })
    }

    /** Show mid-term memory — dated files, not in prompt. */
    private suspend fun memoryMid(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return if (args.isEmpty()) {
            // Show available date files
            val dates = AgentDocs.listMidTermDates(ctx.agentName ?: "MengPaw")
            if (dates.isEmpty()) return ExecutionResult.ok("(无中期记忆)")
            val stats = AgentDocs.midTermStats(ctx.agentName ?: "MengPaw")
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
            val content = AgentDocs.readMidTermMemoryDate(ctx.agentName ?: "MengPaw", date)
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
        val agent = ctx.agentName ?: "MengPaw"
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
                    val file = java.io.File(DataPaths.projectMemoryFile(agent, name))
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
        if (args.size < 2) return ExecutionResult.fail(
            "用法: agent.memory.project.save <项目名> <总结内容>\n" +
            "在项目里程碑或闭环后总结经验, 形成可复用的项目完成模式。"
        )
        val projectName = args[0]
        val report = args.drop(1).joinToString(" ")
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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
        val agent = ctx.agentName ?: "MengPaw"
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

    /** Delete boost.md — Agent has completed initialization. */
    private suspend fun boostDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = ctx.agentName ?: "MengPaw"
        val ok = AgentDocs.deleteBoost(agent)
        return if (ok) ExecutionResult.ok("BOOST.md 已删除。你已完成初始化，不再需要引导文件。")
        else ExecutionResult.ok("BOOST.md 不存在——你早已完成初始化。")
    }

    /** First-run bootstrap ritual — guide the Agent through initial setup. */
    private suspend fun boost(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val boostDoc = docManager.getDoc(AgentDocType.BOOST)
        if (boostDoc.isBlank()) return ExecutionResult.ok(buildString {
            appendLine("(BOOST.md 不存在 — 你已完成初始化)")
            appendLine()
            appendLine("这说明你已经不是第一次醒来了。你的 soul/profile/memory 已经建立。")
            appendLine("继续做你该做的事。")
        })
        return ExecutionResult.ok(boostDoc)
    }

    private suspend fun cli(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val cliDoc = docManager.getDoc(AgentDocType.CLI)
        return ExecutionResult.ok(cliDoc.ifEmpty { "(CLI.md not yet generated)" })
    }

    private suspend fun profile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.PROFILE))
    }

    private suspend fun soul(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.SOUL))
    }

    /** Clean workspace — screenshots, temp files, old checkpoints. Use --dry-run to preview. */
    private suspend fun cleanup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val dryRun = args.contains("--dry-run")
        val agent = ctx.agentName ?: "MengPaw"
        if (dryRun) {
            val report = buildCleanupPreview(agent)
            return ExecutionResult.ok(report)
        }
        val result = com.mengpaw.kernel.agent.DreamEngine.cleanupWorkspace()
        return ExecutionResult.ok(buildString {
            appendLine("清理完成:")
            appendLine("- 删除文件: ${result.filesDeleted} 个")
            appendLine("- 释放空间: ${result.bytesFreed / 1024}KB")
            if (result.dirsCleaned.isNotEmpty()) {
                appendLine("- 清理目录: ${result.dirsCleaned.joinToString(", ")}")
            }
            appendLine()
            appendLine("提示: 用 agent.storage 查看当前空间占用。用 agent.cleanup --dry-run 预览。")
        })
    }

    /** Build a preview of what cleanup would remove. */
    private fun buildCleanupPreview(agent: String): String {
        val sb = StringBuilder("## 可清理内容预览\n\n")
        // Screenshots
        val ssDir = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS)
        if (ssDir.exists()) {
            val ssCount = ssDir.listFiles()?.count { it.isFile } ?: 0
            val ssSize = ssDir.listFiles()?.sumOf { it.length() } ?: 0L
            if (ssCount > 0) sb.appendLine("- 📸 截图: $ssCount 个文件 (${ssSize / 1024}KB)")
        }
        // Checkpoints
        val cpDir = java.io.File(com.mengpaw.kernel.DataPaths.CHECKPOINTS)
        if (cpDir.exists()) {
            val cpCount = cpDir.listFiles()?.count { it.isFile } ?: 0
            val cpSize = cpDir.listFiles()?.sumOf { it.length() } ?: 0L
            if (cpCount > 0) sb.appendLine("- 💾 检查点: $cpCount 个文件 (${cpSize / 1024}KB)")
        }
        // Mid-term memory older than 7 days
        val midDates = AgentDocs.listMidTermDates(agent)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val oldDates = midDates.filter { it < today }
        if (oldDates.isNotEmpty()) sb.appendLine("- 📝 中期记忆: ${oldDates.size} 个历史分片 (用 agent.memory.mid.delete 逐个清理)")
        // Temp files
        val tmpDir = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "tmp")
        if (tmpDir.exists()) {
            val tmpCount = tmpDir.listFiles()?.count { it.isFile } ?: 0
            if (tmpCount > 0) sb.appendLine("- 🗑 临时文件: $tmpCount 个")
        }
        return if (sb.length < 50) "无需要清理的内容。" else sb.toString().trimEnd()
    }

    /** Comprehensive storage usage report — per-directory breakdown. */
    private suspend fun storageReport(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = ctx.agentName ?: "MengPaw"
        return ExecutionResult.ok(buildString {
            appendLine("## 存储用量")
            appendLine()
            // Agent workspace
            val wsDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, agent)
            val wsSize = dirSize(wsDir)
            appendLine("### 工作区 (Agent文档/$agent)")
            appendLine("- 总大小: ${formatSize(wsSize)}")
            // Detail: memory
            val memDir = java.io.File(com.mengpaw.kernel.DataPaths.midTermMemoryDir(agent))
            if (memDir.exists()) {
                val memFiles = memDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()
                val memSize = memFiles.sumOf { it.length() }
                appendLine("  - memory/ ${memFiles.size} 个分片 (${formatSize(memSize)})")
            }
            val ltm = java.io.File(com.mengpaw.kernel.DataPaths.longTermMemoryFile(agent))
            if (ltm.exists()) appendLine("  - 长期记忆: ${formatSize(ltm.length())} (${AgentDocs.readLongTermMemory(agent).lines().count { it.startsWith("## ") }} 条)")
            // Plugins
            val plugDir = java.io.File(com.mengpaw.kernel.DataPaths.PLUGIN_CACHE)
            val plugSize = dirSize(plugDir)
            appendLine()
            appendLine("### 插件仓库")
            appendLine("- 总大小: ${formatSize(plugSize)}")
            // Sessions
            val sessionsDir = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
            val sessionCount = if (sessionsDir.exists()) sessionsDir.listFiles()?.count { it.extension == "json" } ?: 0 else 0
            val sessionSize = if (sessionsDir.exists()) sessionsDir.listFiles()?.sumOf { it.length() } ?: 0L else 0L
            val historyFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
            val historyRecords = try {
                if (historyFile.exists()) Json.parseToJsonElement(historyFile.readText()).jsonArray.size else 0
            } catch (_: Exception) { 0 }
            appendLine()
            appendLine("### 会话")
            appendLine("- 消息文件: $sessionCount 个 (${formatSize(sessionSize)})")
            appendLine("- 历史索引: $historyRecords 条")
            // Screenshots
            val ssDir = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS)
            if (ssDir.exists()) {
                val ssCount = ssDir.listFiles()?.count { it.isFile } ?: 0
                val ssSize = ssDir.listFiles()?.sumOf { it.length() } ?: 0L
                appendLine()
                appendLine("### 截图")
                appendLine("- $ssCount 个文件 (${formatSize(ssSize)})")
                appendLine("- 清理: agent.cleanup")
            }
        })
    }

    private fun dirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach {
            total += if (it.isDirectory) dirSize(it) else it.length()
        }
        return total
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }

    /** Dream mode: organize memories, archive, summarize — never delete. */
    private suspend fun dream(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isNotEmpty() && args[0] == "stats") {
            return ExecutionResult.ok(com.mengpaw.kernel.agent.DreamEngine.dreamStats())
        }
        if (args.isNotEmpty() && args[0] == "history") {
            return ExecutionResult.ok(com.mengpaw.kernel.agent.DreamEngine.dreamHistory())
        }
        // FIX: sessionId → agentName; sessionId 是 UUID 而 DreamEngine 需要 agent 目录名
        val result = com.mengpaw.kernel.agent.DreamEngine.dream(ctx.agentName ?: "MengPaw" ?: "agent-001")
        val cleanup = com.mengpaw.kernel.agent.DreamEngine.cleanupWorkspace()
        return ExecutionResult.ok("""
梦境完成:
- 翻阅记忆: ${result.memoriesReviewed} 条
- 自动标签: ${result.tagsAdded} 个
- 交叉链接: ${result.linksFound} 组
- 归档(30天+): ${result.archived} 条 → Memory.archive.md (原文永久保留)
- 生成摘要: ${result.summarized} 条
- 清理临时文件: ${cleanup.filesDeleted} 个, 释放 ${cleanup.bytesFreed / 1024}KB
""".trimIndent())
    }

    /** Browser plugin development capabilities. */
    private suspend fun browserTools(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(AgentDocManager.Companion.BROWSER_TOOLS_MD)
    }

    /** Cross-session index: search saved session history by keyword. */
    private suspend fun sessions(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
        if (!file.exists()) return ExecutionResult.ok("(no saved sessions)")

        val raw = try { file.readText() } catch (_: Exception) {
            return ExecutionResult.fail("Cannot read session history", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        if (raw.isBlank()) return ExecutionResult.ok("(no sessions)")

        val keyword = args.firstOrNull()?.lowercase()
        val limit = args.getOrNull(1)?.toIntOrNull() ?: 20
        val results = mutableListOf<String>()

        try {
            val arr = Json.parseToJsonElement(raw).jsonArray
            for (el in arr) {
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val preview = obj["preview"]?.jsonPrimitive?.content ?: ""
                val ts = obj["timestamp"]?.jsonPrimitive?.long ?: 0L
                val count = obj["messageCount"]?.jsonPrimitive?.int ?: 0
                val agent = obj["agentName"]?.jsonPrimitive?.content ?: ""
                val compacted = obj["compacted"]?.jsonPrimitive?.boolean ?: false

                if (keyword != null && !title.lowercase().contains(keyword) && !preview.lowercase().contains(keyword)) continue

                val date = if (ts > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ts)) else "?"
                val tag = if (compacted) "[压]" else ""
                results.add("$tag[$agent] $date · $title$tag · ${count}msgs")
            }
        } catch (_: Exception) {
            return ExecutionResult.fail("Session history file is corrupted. 💡 下次启动会自动重置。当前数据可能已备份为 session_history.json.bak。", errorCode = ErrorCodes.ERR_INTERNAL)
        }

        if (results.isEmpty()) return ExecutionResult.ok(
            if (keyword != null) "(no sessions matching '$keyword')" else "(no sessions)"
        )

        val header = if (keyword != null) "会话索引 (匹配 '$keyword', ${results.size}):\n" else "会话索引 (${results.size}):\n"
        return ExecutionResult.ok(header + results.take(limit).joinToString("\n") { "  • $it" })
    }

    /** agent.session.delete <id> — delete a session record and its message file. */
    private suspend fun sessionDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: agent.session.delete <id>\n💡 使用 agent.sessions 先查看会话列表获取 ID。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0]
        val historyFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
        if (!historyFile.exists()) return ExecutionResult.fail("No session history file found.", errorCode = ErrorCodes.ERR_NOT_FOUND)

        return try {
            val raw = historyFile.readText()
            val arr = Json.parseToJsonElement(raw).jsonArray
            val filtered = arr.filter { it.jsonObject["id"]?.jsonPrimitive?.content != id }
            if (filtered.size == arr.size) return ExecutionResult.fail("Session not found: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)

            val newJson = JsonArray(filtered)
            // Atomic write updated history
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            tmp.renameTo(historyFile)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }

            // Delete session message file
            val sessionFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions/$id.json")
            if (sessionFile.exists()) { sessionFile.delete() }

            ExecutionResult.ok("会话 $id 已删除。")
        } catch (e: Exception) {
            ExecutionResult.fail("删除失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.session.archive <id> [--unarchive] — toggle archive state of a session. */
    private suspend fun sessionArchive(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: agent.session.archive <id> [--unarchive]\n💡 归档后会话从默认视图隐藏，可用 --unarchive 恢复。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0]
        val unarchive = args.contains("--unarchive")
        val historyFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
        if (!historyFile.exists()) return ExecutionResult.fail("No session history file found.", errorCode = ErrorCodes.ERR_NOT_FOUND)

        return try {
            val raw = historyFile.readText()
            val arr = Json.parseToJsonElement(raw).jsonArray
            var found = false
            val updated = arr.map { el ->
                val obj = el.jsonObject.toMutableMap()
                if (obj["id"]?.jsonPrimitive?.content == id) {
                    found = true
                    obj.toMutableMap().apply { put("archived", JsonPrimitive(!unarchive)) }
                } else obj
            }
            if (!found) return ExecutionResult.fail("Session not found: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)

            val newJson = JsonArray(updated.map { JsonObject(it) })
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            tmp.renameTo(historyFile)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }

            ExecutionResult.ok(if (unarchive) "会话 $id 已取消归档。" else "会话 $id 已归档。")
        } catch (e: Exception) {
            ExecutionResult.fail("归档失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.session.current — show current session info. */
    private suspend fun sessionCurrent(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
        if (!file.exists()) return ExecutionResult.ok("(no active session)")

        return try {
            val text = file.readText()
            var sid = "(legacy)"
            var msgCount = 0
            try {
                val wrapper = Json.parseToJsonElement(text).jsonObject
                sid = wrapper["sessionId"]?.jsonPrimitive?.content ?: "(legacy)"
                msgCount = wrapper["messages"]?.jsonArray?.size ?: 0
            } catch (_: Exception) {
                // Old format: plain array
                msgCount = Json.parseToJsonElement(text).jsonArray.size
            }
            ExecutionResult.ok("当前会话: $sid\n消息数: $msgCount\n最后修改: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(file.lastModified()))}")
        } catch (e: Exception) {
            ExecutionResult.fail("读取失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** View command audit trail (security feature). */
    private suspend fun audit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val count = args.firstOrNull()?.toIntOrNull() ?: 50
        val entries = com.mengpaw.kernel.cli.Pipeline.getGlobalAuditLog(count)
        if (entries.isEmpty()) return ExecutionResult.ok("(No audit entries)")
        return ExecutionResult.ok(entries.joinToString("\n") { e ->
            "${if (e.success) "OK" else "FAIL"} [${e.sessionId}] ${e.command}: ${e.output.take(80)}"
        })
    }

    // ── File I/O (built-in, no plugin needed) ──────────────────────

    /**
     * Paths the Agent may NEVER write to — protects APK core files, system binaries.
     * Reading from these paths is allowed (Agent needs to inspect its own config/docs).
     *
     * Strategy: deny-list, NOT allow-list. Agent can access everything except:
     * - Non-data system partitions (/system, /vendor)
     * - App private binaries outside its workspace
     */
    private val WRITE_BLOCKED_PREFIXES = listOf(
        "/system/", "/vendor/", "/product/", "/odm/",
        "/data/app/", // installed APKs
        "/data/dalvik-cache/"
    )

    /** Paths blocked from deletion — extends write blocked with critical agent files. */
    private val RM_BLOCKED_PREFIXES = listOf(
        "/system/", "/vendor/", "/product/", "/odm/",
        "/data/app/", "/data/dalvik-cache/",
        // Core agent files — use dedicated commands (agent.memory.rm, etc.) instead
        // soul.md, profile.md, agents.md are deletable via agent.rm (Agent owns them)
    )

    /** Resolve path with traversal protection (canonical path resolves ../ and symlinks). */
    private fun resolvePath(raw: String): java.io.File? {
        val file = if (java.io.File(raw).isAbsolute) java.io.File(raw)
                   else java.io.File(com.mengpaw.kernel.DataPaths.BASE, raw)
        return try { file.canonicalFile } catch (_: Exception) { null }
    }

    /** agent.read <path> — read any file (no restrictions beyond filesystem). */
    private suspend fun readFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.read <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = args.joinToString(" ")
        val file = resolvePath(path)
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!file.exists()) return ExecutionResult.fail("文件不存在: $path", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (file.isDirectory) {
            val listing = file.listFiles()?.take(50)?.joinToString("\n") { f ->
                "${if (f.isDirectory) "📁" else "📄"} ${f.name} (${if (f.isFile) "${f.length()}B" else "-"})"
            } ?: "(空目录)"
            return ExecutionResult.ok("$path:\n$listing")
        }
        return try {
            val content = file.readText().take(100_000)
            ExecutionResult.ok(content)
        } catch (e: Exception) {
            ExecutionResult.fail("读取失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.ls <path> — list files in a directory. Defaults to workspace root. */
    private suspend fun listFiles(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = ctx.agentName ?: "MengPaw"
        val defaultPath = "${com.mengpaw.kernel.DataPaths.AGENTS}/$agent"
        val path = if (args.isEmpty()) defaultPath else args.joinToString(" ")
        val dir = resolvePath(path) ?: return ExecutionResult.fail("路径无效: $path")
        if (!dir.exists()) return ExecutionResult.fail("路径不存在: $path")
        if (!dir.isDirectory) {
            // Single file — show its info
            return ExecutionResult.ok("📄 ${dir.name} — ${dir.length()}B — ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(dir.lastModified()))}")
        }
        val files = dir.listFiles()?.sortedBy { if (it.isDirectory) 0 else 1 } ?: emptyList()
        if (files.isEmpty()) return ExecutionResult.ok("$path/\n(空目录)")
        return ExecutionResult.ok(buildString {
            appendLine("$path/")
            files.forEach { f ->
                val icon = if (f.isDirectory) "📁" else "📄"
                val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))
                appendLine("  $icon ${f.name}$size · $date")
            }
            appendLine()
            appendLine("${files.size} 个项目")
        })
    }

    /** agent.rm <path> — delete a file or empty directory. Blocked on system paths. Requires --force for files. */
    private suspend fun deleteFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flags = args.filter { it.startsWith("--") }
        val pathArgs = args.filter { !it.startsWith("--") }
        val force = flags.contains("--force")
        if (pathArgs.isEmpty()) return ExecutionResult.fail(
            "用法: agent.rm <path> [--force]\n" +
            "删除文件或空目录。文件需要 --force 确认（不可逆）。系统路径受保护。\n" +
            "先预览: agent.ls <path> 查看要删的文件。"
        )
        val path = pathArgs.joinToString(" ")
        val file = resolvePath(path) ?: return ExecutionResult.fail("路径无效: $path")
        if (!file.exists()) return ExecutionResult.fail("文件不存在: $path")
        if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) {
            return ExecutionResult.fail("目录非空: $path (${file.listFiles()?.size ?: 0} 个项目)。\n请先删除目录中的文件，或用 agent.memory.mid.delete 删除中期记忆分片。")
        }
        if (file.isFile && !force) {
            return ExecutionResult.fail(buildString {
                appendLine("⚠️ 即将永久删除文件: $path (${formatSize(file.length())})")
                appendLine()
                appendLine("此操作不可逆。确认删除请执行: agent.rm $path --force")
            })
        }
        val canonical = file.absolutePath
        if (RM_BLOCKED_PREFIXES.any { canonical.startsWith(it) }) {
            return ExecutionResult.fail("禁止删除系统/应用目录: $path")
        }
        return try {
            val size = file.length()
            val ok = file.delete()
            if (ok) ExecutionResult.ok("已删除: $path (${formatSize(size)})\n\n如需恢复，从孪生设备同步: twin.sync")
            else ExecutionResult.fail("删除失败: $path")
        } catch (e: Exception) {
            ExecutionResult.fail("删除异常: ${e.message}")
        }
    }

    /** agent.mkdir <path> — create a directory. */
    private suspend fun makeDir(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.mkdir <path>")
        val path = args.joinToString(" ")
        val dir = resolvePath(path) ?: return ExecutionResult.fail("路径无效: $path")
        if (dir.exists()) return ExecutionResult.fail("已存在: $path")
        return try {
            dir.mkdirs()
            ExecutionResult.ok("已创建目录: $path")
        } catch (e: Exception) {
            ExecutionResult.fail("创建失败: ${e.message}")
        }
    }

    /** agent.write <path> <content> — write file. Blocked on system/app paths only. */
    private suspend fun writeFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: agent.write <path> <content>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = args.first()
        val content = args.drop(1).joinToString(" ")
        val file = resolvePath(path)
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        // Deny-list check: block writes to system/app partitions
        val canonical = file.path
        if (WRITE_BLOCKED_PREFIXES.any { canonical.startsWith(it) }) {
            return ExecutionResult.fail("禁止写入系统/应用目录: $path", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            file.parentFile?.mkdirs()
            // Atomic write via tmp+rename
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            ExecutionResult.ok("已写入: $path (${content.length} 字符)")
        } catch (e: Exception) {
            ExecutionResult.fail("写入失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
