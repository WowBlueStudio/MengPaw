// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.agent.AgentDocs
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话压缩职责（自 SessionManager 拆出 — 400 行文件拆分批次 1）。
 *
 * 覆盖: 压缩保留策略 (MIN 组数保底 + token 预算)、QwenPaw 风格结构化摘要与
 * dialog/YYYY-MM-DD.jsonl 归档、后台预压缩 (v0.28.6 异步化)、
 * 自动摘要落地中期记忆 (v0.32.1+ 自检报告 P1-5)。
 *
 * 同步契约: 所有同步点与 [lock] 共用 — SessionManager 的 addMessage/replaceMessages
 * 监视器与压缩的"快照 diff 合并"必须保持同一把锁, 否则并行 worker 丢消息窗口复活。
 */
internal class SessionCompressor(
    private val lock: Any,
    private val sessionProvider: (String) -> Session?,
    private val activeSessionIdProvider: () -> String?,
    private val updateSession: (String, Session) -> Unit,
    private val agentNameProvider: () -> String
) {
    /** token/字符 粗估系数（同 LlmRequestBuilder.FALLBACK_TOK_PER_CHAR）。 */
    private val TOK_PER_CHAR = 0.25
    /** 至少保留的问答组数（原文优先保底 — 即使超预算）。 */
    private val MIN_KEEP_GROUPS = 3

    // ── 后台预压缩 (v0.28.6): 接近阈值提前后台压缩, buildConversation 主请求不阻塞 ──
    private val inFlightCompressions = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // ── P1-5 自动摘要落地中期记忆 (best-effort, 失败静默) ──
    private val autoSummaryGuard = AutoSummaryMemory.WrittenGuard()

    /**
     * Compress conversation history if it exceeds the message budget.
     * QwenPaw-style: archives raw messages to dialog/YYYY-MM-DD.jsonl before compaction;
     * produces a structured summary with Goal/Progress/KeyDecisions/NextSteps/CriticalContext.
     * When over [maxMessages] (default 50), uses [llmProvider] to generate a structured summary
     * and replaces older messages. Retains recent conversation groups:
     * MIN_KEEP_GROUPS groups unconditionally + more up to the token budget
     * (window × coherence tier 8%/15%/25% — see [splitRetention]).
     *
     * @param specificSessionId If provided, compress this session. Otherwise use active session.
     * @return true if compaction was performed.
     */
    suspend fun compressIfNeeded(
        llmProvider: LlmProvider,
        maxMessages: Int = 50,
        specificSessionId: String? = null
    ): Boolean {
        val sessionId = specificSessionId ?: activeSessionIdProvider() ?: return false
        val session = sessionProvider(sessionId) ?: return false
        if (session.messages.size <= maxMessages) return false
        KernelLog.d("MengPawLatency", "SUM-START $sessionId msgs=${session.messages.size}")

        // ── 保留策略: MIN 组数保底 + MAX token 预算（连贯性档位）──
        // 从最近往回按问答组（user 消息为界）累积保留原文:
        //   - MIN_KEEP_GROUPS 组无条件保留（原文优先, 即使超预算）
        //   - 预算内继续累积直到用尽（预算 = 窗口 × 连贯性档位 8%/15%/25%）
        // 组数随问答大小自动浮动: 大问答保留组数少, 小问答保留多
        // Snapshot BEFORE the suspend LLM call to avoid losing concurrently-added messages
        val snapshot = session.messages.toList()
        val budgetTokens = (com.mengpaw.kernel.PipelineManager.DEFAULT_CONTEXT_WINDOW *
            retentionBudgetRatio(snapshot)).toInt()
        val (toKeep, toCompress) = splitRetention(snapshot, budgetTokens)
        if (toCompress.isEmpty()) { KernelLog.d("MengPawLatency", "SUM-END none"); return false }

        // ── QwenPaw-style: archive raw messages before compaction ──
        archiveRawMessages(toCompress)

        // ── QwenPaw-style: structured summary (长度与保留原文反相关 — 目标占用率 ~60%) ──
        val keptTokens = toKeep.sumOf { (it.content.length * TOK_PER_CHAR).toInt() }
        val summary = summarizeMessagesStructured(
            llmProvider, toCompress, summaryBudgetCharsFor(keptTokens))

        // ── Build compact_summary with dialog path reference ──
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dialogRef = "dialog/$today.jsonl"
        val summaryMsg = Message(
            role = "system",
            content = buildString {
                append("[📋 对话摘要]\n")
                append(summary)
                append("\n[完整历史: $dialogRef — 需要时用 agent.read 查阅]")
            }
        )

        // Preserve any messages added during the LLM call — v0.28.6 异步化竞态加固:
        // 在监视器内以快照为基准做身份 diff (addMessage 的 200 条 removeAt(0) 会破坏
        // 下标对齐, 旧实现 afterSnap.drop(snapshot.size) 在 monitor 外读还有丢消息窗口)
        synchronized(lock) {
            val current = session.messages.toList()
            // 身份 Set (IdentityHashMap — 相等内容不误判为"新消息")
            val snapshotIds = java.util.IdentityHashMap<Message, Boolean>()
            snapshot.forEach { snapshotIds[it] = true }
            val concurrentNew = current.filter { !snapshotIds.containsKey(it) }
            session.messages.clear()
            session.messages.add(summaryMsg)
            session.messages.addAll(toKeep)
            if (concurrentNew.isNotEmpty()) session.messages.addAll(concurrentNew)
            updateSession(sessionId, session)
        }
        KernelLog.d("MengPawLatency", "SUM-END done msgs=${session.messages.size}")
        // P1-5: 会话收尾自动摘要 → 中期记忆 — 压缩成功即把摘要自动写入当期中期分片,
        // 规则触发而非模型自觉 agent.memory.record (长对话末尾模型常忘写)。
        writeCompactionSummaryToMidTerm(sessionId, session.scope, summary)
        return true
    }

    /** 消息数 ≥ threshold-margin 且无在途压缩时, 在 [scope] 后台启动压缩. µs 级返回. */
    fun scheduleCompressionIfNeeded(
        sessionId: String,
        scope: CoroutineScope,
        llmProvider: LlmProvider,
        threshold: Int = 50,
        margin: Int = 8
    ) {
        val session = sessionProvider(sessionId) ?: return
        if (session.messages.size < threshold - margin) return
        if (inFlightCompressions.containsKey(sessionId)) return
        inFlightCompressions[sessionId] = scope.launch {
            try {
                compressIfNeeded(llmProvider, threshold, sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                inFlightCompressions.remove(sessionId)
            }
        }
    }

    /**
     * 关键路径兜底: 在途压缩不 join (本轮放行 — 快照一致, 压缩与请求并发无害);
     * 无在途且仍超阈值时同步压缩 (最终兜底, 确保消息预算).
     */
    suspend fun awaitCompressionIfNeeded(
        llmProvider: LlmProvider,
        threshold: Int = 50,
        sessionId: String
    ): Boolean {
        val inFlight = inFlightCompressions[sessionId]
        if (inFlight != null && inFlight.isActive) return false
        return compressIfNeeded(llmProvider, threshold, sessionId)
    }

    /**
     * 把压缩摘要追加写入当期中期记忆分片 (memory_{date}.md)。
     * - 复用 AgentDocs.appendMidTermMemory 写入队列 — 与 agent.memory.record 同一
     *   落盘路径/格式, LLM 响应返回后由 AgentEngine.flushMidTermMemoryQueue 刷盘;
     * - 幂等: AutoSummaryMemory.WrittenGuard 以 会话 id + 折叠次数 去重;
     * - 零待命并行 worker (swarm/mission) 不写 — 与 AgentMemoryExecutor 写屏蔽一致,
     *   防止 worker 对话向 Agent 中期记忆注入噪音;
     * - 写入失败静默 (best-effort), 不放异常, 不阻塞压缩主路径。
     */
    private fun writeCompactionSummaryToMidTerm(sessionId: String, scope: String, summary: String) {
        if (scope == "swarm" || scope == "mission" || summary.isBlank()) return
        try {
            val round = autoSummaryGuard.nextOrdinal(sessionId)
            if (!autoSummaryGuard.shouldWrite(sessionId, round)) return
            AgentDocs.appendMidTermMemory(agentNameProvider(), AutoSummaryMemory.buildEntry(summary, sessionId, round))
        } catch (e: Exception) {
            KernelLog.w("History", "writeCompactionSummaryToMidTerm: ${e.message}")
        }
    }

    /**
     * Archive raw messages to dialog/YYYY-MM-DD.jsonl before compaction.
     * Guarantees no data loss — Agent can always retrieve full history via read_file.
     */
    private fun archiveRawMessages(messages: List<Message>) {
        try {
            val dir = java.io.File(DataPaths.dialogArchiveDir(agentNameProvider())).also { it.mkdirs() }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = java.io.File(dir, "$today.jsonl")
            // JSONL append — one line per message, chronologically
            val lines = messages.map { msg ->
                buildJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                }.toString()
            }
            // Append to JSONL file
            file.appendText(lines.joinToString("\n") + "\n")
        } catch (e: Exception) {
            KernelLog.w("History", "archiveRawMessages: ${e.message}")
        }
    }

    /**
     * QwenPaw-style structured summary via LLM.
     * Produces: Goal / Progress / KeyDecisions / NextSteps / CriticalContext.
     * Merges with any existing summary for incremental updates.
     */
    private suspend fun summarizeMessagesStructured(
        llmProvider: LlmProvider,
        messages: List<Message>,
        summaryBudgetChars: Int = 600
    ): String {
        val conversationText = messages.joinToString("\n") { "[${it.role}] ${it.content.take(500)}" }

        // Check for existing compact_summary in the messages (merge case)
        val existingSummary = messages.firstOrNull { it.role == "system" && it.content.startsWith("[📋") }
        val mergeInstruction = if (existingSummary != null) {
            "\n## 已有摘要 (合并基础)\n${existingSummary.content}\n\n请将新对话合并到已有摘要中，更新各字段。"
        } else ""

        val summaryPrompt = listOf(
            mapOf(
                "role" to "user",
                "content" to """
提取以下对话历史的结构化摘要。输出纯文本(不要JSON/Markdown标题/代码块)，严格按此格式:

目标: <一句话描述用户想要达成什么>
进展: <已完成/进行中/被阻塞的具体事项>
关键决策: <做出的决策及其理由，用分号分隔>
下一步: <接下来要做什么>
关键上下文: <继续任务必须知道的信息：文件路径、函数名、关键技术栈、错误信息>

规则:
- 保留精确的文件路径、函数名、命令名和错误消息
- "进展"和"关键上下文"不超过各3个要点
- 如果用户只做了一个简单查询，摘要应同样简短
- 每行前面不要加"- "列表符号，直接写字段名和内容
$mergeInstruction

## 对话记录
$conversationText
""".trimIndent()
            )
        )
        return try {
            llmProvider.completeWithMessages(summaryPrompt).take(summaryBudgetChars)
        } catch (e: Exception) {
            KernelLog.w("History", "summarize failed: ${e.message}")
            "目标: (参见完整历史)\n进展: 对话已压缩\n关键决策: 无\n下一步: 继续对话\n关键上下文: 见 dialog/归档文件"
        }
    }

    /**
     * Deprecated — kept for backward compatibility.
     * Calls the LLM to produce a simple summary. Prefer [summarizeMessagesStructured].
     */
    @Deprecated("Use summarizeMessagesStructured for QwenPaw-style structured output")
    private suspend fun summarizeMessages(
        llmProvider: LlmProvider,
        messages: List<Message>
    ): String {
        val conversationText = messages.joinToString("\n") { "[${it.role}] ${it.content}" }
        val summaryPrompt = listOf(
            mapOf(
                "role" to "user",
                "content" to "Summarize the following conversation history concisely. " +
                    "Capture key decisions, actions taken, important context, and outcomes. " +
                    "Keep the summary under 500 words.\n\n$conversationText"
            )
        )
        return llmProvider.completeWithMessages(summaryPrompt)
    }

    /**
     * 从最近往回按问答组（user 消息为界）切分保留原文。
     * @return Pair(保留原文, 待压缩) — 保持原顺序。
     */
    private fun splitRetention(messages: List<Message>, budgetTokens: Int): Pair<List<Message>, List<Message>> {
        val keep = mutableListOf<Message>()
        var keptTokens = 0
        var groups = 0
        var idx = messages.size - 1
        while (idx >= 0) {
            // 收集一组: 从 idx 往回直到（不含）上一个 user 消息
            val group = mutableListOf<Message>()
            var boundary = idx
            while (boundary >= 0) {
                group.add(0, messages[boundary])
                if (messages[boundary].role == "user") break
                boundary--
            }
            val groupTokens = group.sumOf { (it.content.length * TOK_PER_CHAR).toInt() }
            // MIN 保底（原文优先）或预算内 → 保留; 否则停止
            if (groups < MIN_KEEP_GROUPS || keptTokens + groupTokens <= budgetTokens) {
                keep.addAll(0, group)
                keptTokens += groupTokens
                groups++
                idx = boundary - 1
            } else {
                break
            }
        }
        val toCompress = messages.dropLast(keep.size)
        return keep to toCompress
    }

    /**
     * 连贯性信号 → 保留预算档位（轻量启发式, 零 LLM 开销）。
     * 高 25%: 工作深度（最近 ~40 条消息内同一 Command 命令 ≥3 次）或调试态（最近 ~20 条含错误关键字）
     * 中 15%: 产出规模（最近消息平均 >2000 字符）
     * 低 8%: 默认（普通问答, 主题轮换快）
     */
    private fun retentionBudgetRatio(messages: List<Message>): Double {
        val recent = messages.takeLast(40)
        // 工作深度: 同一命令出现 >= 3 次
        val cmds = recent.filter { it.role == "assistant" && it.content.startsWith("Command:") }
            .map { it.content.substringAfter("Command: ").substringBefore("\n").take(40) }
        if (cmds.groupingBy { it }.eachCount().values.any { it >= 3 }) return 0.25
        // 调试态: 最近 5 组（~20 条）含错误关键字
        val debugMarkers = listOf("Error", "失败", "超时", "再试", "修正")
        if (recent.takeLast(20).any { m -> debugMarkers.any { m.content.contains(it) } }) return 0.25
        // 产出规模: 平均消息 > 2000 字符
        val avgSize = recent.map { it.content.length }.average()
        return if (avgSize > 2000) 0.15 else 0.08
    }

    /**
     * 摘要长度反相关 — 保留原文越多摘要越短。
     * 预算 = 0.6×窗口 − 保留原文 token 折算字符, 上下限 [300, 1200]。
     * 注: 1200 字符上限 ≈ 300 token, 对 131K 窗口占比极小 — "60% 目标占用率"
     * 仅在保留原文接近窗口上限时才有意义; 实际约束是"摘要不喧宾夺主"。
     */
    private fun summaryBudgetCharsFor(keptTokens: Int): Int {
        val targetTokens = (com.mengpaw.kernel.PipelineManager.DEFAULT_CONTEXT_WINDOW * 0.60).toInt()
        val summaryTokens = targetTokens - keptTokens
        return (summaryTokens / TOK_PER_CHAR).toInt().coerceIn(300, 1200)
    }
}
