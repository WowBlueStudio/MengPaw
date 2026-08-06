// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import kotlinx.serialization.json.*

/**
 * agent.* 会话索引命令执行器 — sessions/session.delete/session.archive/session.current
 * (拆自 AgentExecutor, 400 行文件拆分)。操作 session_history.json 索引 + 消息文件。
 */
internal class AgentSessionCommands {

    /** Cross-session index: search saved session history by keyword. */
    internal suspend fun sessions(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun sessionDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
            // 标准原子写: tmp 写好后再覆盖 — rename 失败不丢原文件 (旧写法先删后搬)
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), historyFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
            }

            // Delete session message file
            val sessionFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions/$id.json")
            if (sessionFile.exists()) { sessionFile.delete() }

            ExecutionResult.ok("会话 $id 已删除。")
        } catch (e: Exception) {
            ExecutionResult.fail("删除失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.session.archive <id> [--unarchive] — toggle archive state of a session. */
    internal suspend fun sessionArchive(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
            // 标准原子写: Files.move 覆盖 (Windows 上 File.renameTo 无法覆盖已存在目标)
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), historyFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
            }

            ExecutionResult.ok(if (unarchive) "会话 $id 已取消归档。" else "会话 $id 已归档。")
        } catch (e: Exception) {
            ExecutionResult.fail("归档失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.session.current — show current session info. */
    internal suspend fun sessionCurrent(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
}
