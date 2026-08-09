// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.screens.model.AgentTrace
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

// ── 序列化 + 历史文件 I/O + 整理纯函数 — 拆自 SessionPersistenceService.kt (2026-08-06, 批次4) ──

// ── Serialization helpers ──

internal fun messagesToJson(msgs: List<ChatMessageUi>): List<MessageData> {
    return msgs.mapNotNull { msg ->
        when (msg) {
            is ChatMessageUi.User -> MessageData(
                type = "user", text = msg.content, attachments = msg.attachments
            )
            is ChatMessageUi.Agent -> MessageData(
                type = "agent", text = msg.content,
                executionMode = msg.executionMode, agentRef = msg.agentRef
            )
            is ChatMessageUi.AgentWithTrace -> MessageData(
                type = "agent_trace", text = msg.finalContent,
                traces = msg.traces.map { t ->
                    TraceData(step = t.step, thought = t.thought,
                        action = t.action ?: "", observation = t.observation ?: "")
                },
                executionMode = msg.executionMode, agentRef = msg.agentRef
            )
            is ChatMessageUi.AgentStep -> MessageData(
                type = "agent_step", text = msg.content,
                step = msg.step, thought = msg.thought, action = msg.action ?: "",
                isFinal = msg.isFinal,
                executionMode = msg.executionMode, agentRef = msg.agentRef
            )
            is ChatMessageUi.ThinkingProcess -> MessageData(
                type = "thinking_process", text = "",
                isRunning = msg.isRunning, collapsed = msg.collapsed,
                steps = msg.steps.map { s ->
                    ProcessStepData(
                        thought = s.thought,
                        tools = s.tools.map { t ->
                            ProcessToolData(t.command, t.actionInput, t.observation, t.isError)
                        }
                    )
                },
                executionMode = msg.executionMode, agentRef = msg.agentRef
            )
            is ChatMessageUi.FinalAnswer -> MessageData(
                type = "final_answer", text = msg.content,
                isRunning = msg.isRunning,
                executionMode = msg.executionMode, agentRef = msg.agentRef
            )
            is ChatMessageUi.CommandResult -> MessageData(
                type = "command", text = msg.content, isError = msg.isError
            )
            else -> null
        }
    }
}

internal fun jsonToMessages(jsonStr: String): List<ChatMessageUi> {
    return try {
        val dataList: List<MessageData> = json.decodeFromString(jsonStr)
        dataList.mapNotNull { data ->
            if (data.type != "thinking_process" && data.text.isBlank()) return@mapNotNull null
            when (data.type) {
                "user" -> ChatMessageUi.User(data.text, attachments = data.attachments)
                "agent" -> ChatMessageUi.Agent(data.text,
                    executionMode = data.executionMode?.ifEmpty { null },
                    agentRef = data.agentRef?.ifEmpty { null })
                "agent_trace" -> {
                    val traces = data.traces.map { t ->
                        AgentTrace(t.step, t.thought,
                            t.action.ifEmpty { null },
                            t.observation.ifEmpty { null })
                    }
                    ChatMessageUi.AgentWithTrace(data.text, traces, isRunning = false,
                        executionMode = data.executionMode?.ifEmpty { null },
                        agentRef = data.agentRef?.ifEmpty { null })
                }
                "agent_step" -> ChatMessageUi.AgentStep(
                    step = data.step, thought = data.thought,
                    action = data.action.ifEmpty { null },
                    content = data.text, isRunning = false, isFinal = data.isFinal,
                    executionMode = data.executionMode?.ifEmpty { null },
                    agentRef = data.agentRef?.ifEmpty { null }
                )
                "thinking_process" -> ChatMessageUi.ThinkingProcess(
                    steps = data.steps.map { s ->
                        ChatMessageUi.ProcessStep(
                            thought = s.thought,
                            tools = s.tools.map { t ->
                                ChatMessageUi.ProcessTool(t.command, t.actionInput, t.observation, t.isError)
                            }
                        )
                    },
                    isRunning = data.isRunning, collapsed = data.collapsed,
                    executionMode = data.executionMode?.ifEmpty { null },
                    agentRef = data.agentRef?.ifEmpty { null }
                )
                "final_answer" -> ChatMessageUi.FinalAnswer(
                    content = data.text, isRunning = data.isRunning,
                    executionMode = data.executionMode?.ifEmpty { null },
                    agentRef = data.agentRef?.ifEmpty { null }
                )
                "command" -> ChatMessageUi.CommandResult(data.text, isError = data.isError)
                else -> null
            }
        }
    } catch (_: Exception) { emptyList() }
}

internal fun atomicWriteJson(file: File, jsonStr: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    try {
        tmp.writeText(jsonStr)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    } catch (e: Exception) {
        KernelLog.w("AgentVM", "atomicWriteJson: ${e.message}")
        try { tmp.delete() } catch (_: Exception) {}
    }
}

// ── Session history persistence ──

internal fun loadSessionHistoryFromDisk(): List<SessionPersistenceService.SessionRecord> {
    return try {
        val file = sessionHistoryFile
        if (file.exists()) {
            val text = file.readText()
            if (text.isNotBlank()) {
                return json.decodeFromString<List<SessionPersistenceService.SessionRecord>>(text)
            }
        }
        emptyList()
    } catch (e: Exception) {
        KernelLog.w("AgentViewModel", "Corrupted session_history.json, resetting: ${e.message}")
        try { sessionHistoryFile.delete() } catch (_: Exception) {}
        emptyList()
    }
}

internal fun saveSessionHistoryToDisk(records: List<SessionPersistenceService.SessionRecord>) {
    try {
        val file = sessionHistoryFile
        if (file.exists() && file.length() > 0) {
            val bak = File(file.parentFile, "${file.name}.bak")
            try { file.copyTo(bak, overwrite = true) } catch (_: Exception) {}
        }
        val jsonStr = json.encodeToString(ListSerializer(SessionPersistenceService.SessionRecord.serializer()), records)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(jsonStr)
        tmp.renameTo(file)
        if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
    } catch (e: Exception) {
        KernelLog.w("AgentViewModel", "Failed to save session history: ${e.message}")
    }
}

private val sessionHistoryFile: File
    get() = File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")

// ── 历史整理纯函数 ──

/** 过滤孤儿会话记录 (无对应消息文件 / 空消息计数), 保留当前会话。 */
internal fun cleanupOrphanRecords(
    records: List<SessionPersistenceService.SessionRecord>,
    sessionsDir: File,
    currentSessionId: String
): List<SessionPersistenceService.SessionRecord> = records.filter { record ->
    val sessionFile = File(sessionsDir, "${record.id}.json")
    if (!sessionFile.exists() && record.id != currentSessionId) return@filter false
    if (record.messageCount <= 0 && record.id != currentSessionId) return@filter false
    true
}

/** 去重: 同 (agentName|title) 只留最新一条。返回 (清洗后列表, 移除数)。 */
internal fun dedupRecords(records: List<SessionPersistenceService.SessionRecord>): Pair<List<SessionPersistenceService.SessionRecord>, Int> {
    val list = records.toMutableList()
    val seen = mutableMapOf<String, SessionPersistenceService.SessionRecord>()
    val toRemove = mutableSetOf<String>()
    for (record in list.sortedByDescending { it.timestamp }) {
        val key = "${record.agentName}|${record.title}"
        val existing = seen[key]
        if (existing != null) {
            if (record.timestamp >= existing.timestamp) {
                toRemove.add(existing.id)
                seen[key] = record
            } else {
                toRemove.add(record.id)
            }
        } else {
            seen[key] = record
        }
    }
    return (if (toRemove.isNotEmpty()) list.filter { it.id !in toRemove } else list) to toRemove.size
}

/** 修复截断 markdown — 未闭合代码围栏 / 粗体 / 斜体 补全 (异常中断导致)。 */
internal fun repairMarkdown(text: String): String {
    var fixed = text
    val fenceCount = fixed.count { it == '`' } / 3
    if (fenceCount % 2 != 0) {
        fixed = fixed.trimEnd() + "\n```"
    }
    val boldCount = fixed.split("**").size - 1
    if (boldCount % 2 != 0) {
        fixed = fixed.trimEnd() + "**"
    }
    val italicCount = fixed.replace("**", "").count { it == '*' }
    if (italicCount % 2 != 0) {
        fixed = fixed.trimEnd() + "*"
    }
    return fixed
}

/** Load a session's messages from its per-session file. */
internal fun loadSessionMessagesFromDisk(sessionId: String): List<ChatMessageUi> {
    try {
        val file = File(com.mengpaw.kernel.DataPaths.BASE, "sessions/$sessionId.json")
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        return jsonToMessages(text)
    } catch (_: Exception) { return emptyList() }
}

// ── current_session.json 读取 / 恢复辅助 (语义与原 Service 内联实现逐行对齐) ──

/** current_session.json 读取结果。 */
internal sealed interface CurrentSessionRead {
    /** 解码成功 (消息可能为空 — 原语义: 空消息不恢复也不删文件)。 */
    data class Ok(val msgs: List<ChatMessageUi>, val sessionId: String?) : CurrentSessionRead
    /** 文件缺失或内容空白 — 不删文件。 */
    object Missing : CurrentSessionRead
    /** 内容损坏 (数组解码失败) — 由调用方删文件重置。 */
    object Corrupt : CurrentSessionRead
}

/** 读取并解码 current_session.json (旧数组格式自动兼容)。 */
internal fun readCurrentSessionFile(): CurrentSessionRead {
    return try {
        val file = File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
        if (!file.exists()) return CurrentSessionRead.Missing
        val text = file.readText()
        if (text.isBlank()) return CurrentSessionRead.Missing

        var restoredId: String? = null
        val arrText: String = try {
            val wrapper = json.decodeFromString<SessionPersistenceData>(text)
            restoredId = wrapper.sessionId.takeIf { it.isNotBlank() && it != "null" }
            json.encodeToString(ListSerializer(MessageData.serializer()), wrapper.messages)
        } catch (_: Exception) {
            text
        }

        val msgs = jsonToMessages(arrText)
        if (msgs.isNotEmpty()) CurrentSessionRead.Ok(msgs, restoredId)
        else CurrentSessionRead.Missing
    } catch (_: Exception) {
        CurrentSessionRead.Corrupt
    }
}

/** 恢复中断消息: 运行中的 AgentWithTrace → 打断提示文本, 尾部追加系统恢复消息。 */
internal fun recoverInterruptedMessages(msgs: List<ChatMessageUi>): Pair<List<ChatMessageUi>, Boolean> {
    val recovered = msgs.toMutableList()
    var wasStuck = false
    for (i in recovered.indices) {
        val m = recovered[i]
        if (m is ChatMessageUi.AgentWithTrace && m.isRunning) {
            recovered[i] = ChatMessageUi.Agent(
                "智能体生成被打断，请回复指令以继续。",
                executionMode = m.executionMode,
                agentRef = m.agentRef
            )
            wasStuck = true
        }
    }
    if (wasStuck) {
        recovered.add(ChatMessageUi.System("上次会话异常中断，已自动恢复。"))
    }
    return recovered to wasStuck
}

/** 消息列表 → 引擎对话历史 (user/assistant 对; 运行中的 trace 跳过)。 */
internal fun toEngineConversation(msgs: List<ChatMessageUi>): List<Pair<String, String>> =
    msgs.mapNotNull { msg ->
        when (msg) {
            is ChatMessageUi.User -> "user" to msg.content
            is ChatMessageUi.Agent -> "assistant" to msg.content
            is ChatMessageUi.AgentWithTrace -> {
                if (msg.isRunning) null
                else "assistant" to msg.finalContent
            }
            else -> null
        }
    }

/** 从 current_session.json 读取引擎会话 ID + 上一引擎会话 ID (损坏 → null)。 */
internal fun readEngineSessionIds(): Pair<String?, String?> {
    val file = File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
    val restoredId = try {
        json.decodeFromString<SessionPersistenceData>(file.readText()).sessionId.takeIf { it.isNotBlank() && it != "null" }
    } catch (_: Exception) { null }
    val prevEngineId = try {
        json.decodeFromString<SessionPersistenceData>(file.readText()).engineSessionId.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
    return restoredId to prevEngineId
}
