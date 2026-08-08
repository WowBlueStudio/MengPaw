// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.namespace.NotifyBus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 一条进化失败记录 — 失败模式库的基础单元。
 * repeatCount 是绩效系统的核心指标:同一模式(命令+错误码)出现第 2 次起即"复现"。
 */
@Serializable
data class EvolutionFailure(
    val id: String,
    val timestamp: Long,
    val agentName: String,
    /** 失败的命令全名, 如 "fs.cat"、"agent.memory.keep"。未知命令时为原始输入。 */
    val command: String,
    /** ErrorCodes 或 ErrorType 名, 如 "ERR_INVALID_INPUT"、"TOOL_CALL_FAILED"。 */
    val errorCode: String,
    val message: String,
    /** 来源: "Pipeline" / "AgentEngine" / "UserCorrection"。 */
    val source: String,
    /** 该失败模式(命令+错误码)在记录中出现过的次数。 */
    val repeatCount: Int = 1,
    /** 是否已被 Agent 沉淀修正(经 evolution.mark-corrected)。 */
    val corrected: Boolean = false
)

/**
 * 一条用户反应切片 — "用户分身"的数据源。
 * Agent 在 L3(用户视角)提问时检索此档案, 借用"用户看到错误时的反应"审视自己。
 */
@Serializable
data class UserReaction(
    val id: String,
    val timestamp: Long,
    val agentName: String,
    /** 用户纠正原文(或撤回动作标记)。 */
    val correction: String,
    /** 上下文切片: 上一条 Agent 回复摘要。 */
    val contextSnippet: String,
    /** 当前任务(用户消息全文)。 */
    val task: String
)

/** 一条会话幻觉率记录 — 持久化到 veracity.jsonl (每行一条, 按会话)。 */
@Serializable
data class VeracityRecord(
    val agentName: String,
    /** 本会话失败命令总数。 */
    val totalFailures: Int,
    /** 本会话未在 Final Answer 中如实提及的失败数。 */
    val unmentionedFailures: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 内置"新手常见错误"种子 — 预置失败模式, 非真实失败记录。
 * 新 Agent 首次 evolution.audit 即可看到常见错误及教训, 不必等失败模式库从零积累。
 * 命中判定: command == prefix 或 command 以 "prefix." 开头 (如 "agent.read" 命中 "agent.read").
 */
data class SeedPattern(
    val id: Int,
    /** 命中前缀 (可多个) — 如 "agent.read"; 原生命令种子用 ["ls","dir","cat"]。 */
    val prefixes: List<String>,
    /** 错误描述 — 常见错误长什么样。 */
    val description: String,
    /** 教训 — 正确做法。 */
    val lesson: String
) {
    fun matches(command: String): Boolean =
        prefixes.any { command == it || command.startsWith("$it.") }
}

/**
 * 进化系统存储层 — 失败模式库 + 用户反应档案。
 *
 * ## Design(仿 ErrorCollector)
 * - 内存环形缓冲最近 50 条失败记录(全局, 按 agent 字段过滤)。
 * - JSON-lines 持久化 `{AGENTS}/{agent}/evolution/failures.jsonl`(按 agent 分文件);
 *   无主记录 (agentName=null → 保留字 "default") 归 `{BASE}/进化档案/` —
 *   绝不落 Agent文档/ 下, 防被 Agent 发现逻辑误判为假 Agent (v0.34.x 修复)。
 * - 用户反应以追加式 Markdown 落盘 `reactions.md`, 供 Agent 直接读取。
 * - 所有写入使用原子操作 (tmp + rename, 全项目写入铁律), 防崩溃损坏。
 * - 所有方法线程安全且永不抛异常。
 *
 * ## 失败模式匹配(绩效核心)
 * key = command + errorCode;同一 key 再次出现 → repeatCount++,
 * 第 2 次起标记为"复现", 由钩子/引导升级为深层省察钩子。
 */
object EvolutionStore {

    private const val MAX_MEMORY = 50
    const val DEFAULT_AGENT = "default"
    /** 回合内重试循环判定阈值 (2026-08-08): 同命令同错误码失败满 3 次即注入停指令。 */
    const val RETRY_LOOP_THRESHOLD = 3

    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private val buffer = ConcurrentLinkedQueue<EvolutionFailure>()
    private var nextId = 0L

    /** 失败模式统计 key → 出现次数(内存, 持续累计)。 */
    private val repeatIndex = mutableMapOf<String, Int>()
    private val lock = Any()

    /**
     * 内置失败模式种子库 — 新手常见错误预防清单 (P1-4)。
     * 非真实失败记录: 不进 repeatIndex, 不写 failures.jsonl; 命中命令前缀时,
     * 失败记录 message 自动附"命中内置种子模式 #N"教训提示, stats/audit 列表展示。
     */
    val SEED_PATTERNS: List<SeedPattern> = listOf(
        SeedPattern(1, listOf("agent.read"), "把自然语言描述当文件路径写进 agent.read",
            "agent.read 的参数是工作区内真实文件路径 (如 agents/xxx.md); 不确定路径先 agent.ls 列出, 不要传自然语言描述"),
        SeedPattern(2, listOf("agent.ls"), "把自然语言描述当目录路径写进 agent.ls",
            "agent.ls 参数是目录路径; 不带参数即列出工作区根目录, 先列目录确认结构再定位文件"),
        SeedPattern(3, listOf("agent.write"), "写操作后没有读回验证",
            "agent.write 后必须用 agent.read 读回验证内容落盘正确, 再继续依赖该文件的后续步骤"),
        SeedPattern(4, listOf("agent.memory.keep"), "调用命令没带 Action Input (缺必需参数)",
            "命令调用必须带全参数 (Action Input): agent.memory.keep <内容> 的内容不可省略; 拿不准用法先 agent.cli"),
        SeedPattern(5, listOf("agent.memory"), "把 JSON 对象直接当 Action Input 粘贴",
            "Action Input 是位置参数 (如 <路径> <内容>), 不是 JSON 对象; 结构化数据先 agent.write 写成文件再引用"),
        SeedPattern(6, listOf("ls", "dir", "cat"), "用 shell 原生命令 dir/ls/cat 操作工作区文件",
            "工作区文件操作用 agent.read/agent.ls/agent.write (带沙箱校验), 不要用 shell 原生命令; shell 只用于系统级任务"),
        SeedPattern(7, listOf("agent.rm"), "删除文件前没有确认目标",
            "agent.rm 不可逆; 删除前先 agent.ls 确认路径与目标确实是该文件, 再执行")
    )

    /** 已自动升级为框架缺陷的 key (agent|prefix|errorCode) — 每进程只写一次, 防刷屏。 */
    private val autoFeedbackKeys = mutableSetOf<String>()

    // ── 会话真实度统计 (P0, 2026-08-08 自检) ─────────────────────────
    // 进程内累计: agent → (总失败命令数, 未在 Final Answer 中如实提及的失败数)。
    // 检测启发式: Final Answer 含该失败错误码, 或含命令名+失败词 → 视为如实提及。
    private val veracityTotals = mutableMapOf<String, Pair<Int, Int>>()
    private val veracityLock = Any()
    private var veracityLoaded = false
    // 高频自然语言失败表述 (2026-08-08 扩充): 静默门禁引导 Agent 自然语言汇报后,
    // 检测必须覆盖"没成功/报错/没能"等口语化措辞, 否则如实汇报也会被误判为未提及。
    private val FAILURE_WORDS = listOf(
        "失败", "错误", "Error", "error", "failed", "无法", "未能", "没能",
        "没成功", "未成功", "不成功", "报错", "出错", "拒绝", "未完成"
    )

    // ── 失败记录 ────────────────────────────────────────────────────

    /**
     * 记录一次失败。返回创建的条目(带 repeatCount)。
     * agentName 为空时落到 "default" 档案。
     *
     * 附带两路自动提示 (P1-4):
     * 1. 命令前缀命中内置种子 → message 附"命中内置种子模式 #N: <教训>"。
     * 2. 已沉淀修正 (markCorrected) 的同前缀错误仍复发 ≥2 次 → 自动升级为框架缺陷,
     *    写入 evolution.report 同款反馈通道 (feedback 目录 md 落盘 + NotifyBus 推送),
     *    message 附落盘路径提示。
     */
    fun recordFailure(
        agentName: String?,
        command: String,
        errorCode: String,
        message: String,
        source: String
    ): EvolutionFailure {
        return try {
            val agent = agentFileOf(agentName)
            val key = "$agent|$command|$errorCode"
            val entry = synchronized(lock) {
                val count = (repeatIndex[key] ?: 0) + 1
                repeatIndex[key] = count
                val cmd = command.take(120)
                // 1) 种子命中提示 — 内置失败模式对照自查, 新手错误就地消化
                val seedHint = matchSeeds(cmd).joinToString("; ") {
                    "命中内置种子模式 #${it.id}: ${it.lesson}"
                }
                // 2) 复现缺陷检测 — 沉淀修正后同型错误仍复发 → 自动升级框架缺陷
                val defectHint = detectRecurrenceDefect(agent, cmd, errorCode, count)
                val e = EvolutionFailure(
                    id = "evo_${nextId++}",
                    timestamp = System.currentTimeMillis(),
                    agentName = agent,
                    command = cmd,
                    errorCode = errorCode.take(60),
                    message = buildString {
                        append(message.take(300))
                        if (seedHint.isNotEmpty()) append("\n[种子] $seedHint")
                        if (defectHint != null) append("\n[缺陷] $defectHint")
                    }.take(600),
                    source = source.take(60),
                    repeatCount = count
                )
                buffer.add(e)
                while (buffer.size > MAX_MEMORY) { buffer.poll() ?: break }
                e
            }
            appendToFile(agent, entry)
            entry
        } catch (_: Exception) {
            EvolutionFailure("evo_err", System.currentTimeMillis(), agentFileOf(agentName), command, errorCode, message, source)
        }
    }

    /**
     * 复现模式强制处理提醒 (P1 闭环, 2026-08-08 自检): 同 agent 同命令+错误码复现 ≥2 次
     * 且未沉淀修正时返回提醒文本, 由 AgentReActLoop 注入失败 Observation, 强制 Agent
     * 当场二选一 (evolution.learn.command 登记 or agent.memory.keep 沉淀)。未触发返回 null。
     * 永不抛异常。
     */
    fun recurrenceReminder(agentName: String?, command: String, errorCode: String): String? {
        return try {
            val agent = agentFileOf(agentName)
            val key = "$agent|${command.take(120)}|$errorCode"
            val count = synchronized(lock) { repeatIndex[key] ?: 0 }
            if (count < 2) return null
            val prefix = commandPrefixOf(command)
            if (prefix.isNotBlank() && hasCorrectedLesson(agent, prefix)) return null // 已修正不再强制
            val escalate = count >= 3
            buildString {
                append(if (escalate) "🚨 此错误模式已复现 $count 次且仍未沉淀修正 — 必须立即处理, 不得继续同类操作:\n"
                       else "⚠️ 此错误模式已复现 $count 次且未沉淀修正, 请当场处理 (二选一, 完成后继续任务):\n")
                append("  ① evolution.learn.command $command <正确用法> [--keywords 同义词,逗号分隔] — 登记进指令集\n")
                append("  ② agent.memory.keep <教训> — 沉淀进长期记忆\n")
                append(if (escalate) "先完成 ①② 之一再继续; 用 evolution.audit 查看沉淀状态。"
                       else "两者都做更好; 用 evolution.audit 可随时查看沉淀状态。")
            }
        } catch (_: Exception) { null }
    }

    /**
     * 回合内重试循环停指令 (2026-08-08, 对齐 QwenPaw RETRY LOOP DETECTED, qwen-code PR #3178):
     * 同一命令同一错误码在**本次任务**内失败 ≥ [RETRY_LOOP_THRESHOLD] 次 → 返回停指令文本,
     * 要求 Agent 立即停止重试、重查用法、换根本不同的方法, 或向用户如实说明。
     * 与 [recurrenceReminder] 的区别: 后者是跨会话复现 (进化沉淀二选一); 本指令是回合内空转干预。
     * 已注入过 (alreadyNotified) 返回 null — 防每轮刷屏。纯函数可单测, 永不抛异常。
     */
    fun retryLoopDirective(
        commandLine: String,
        errorCode: String,
        retryCount: Int,
        alreadyNotified: Boolean
    ): String? {
        return try {
            if (commandLine.isBlank() || retryCount < RETRY_LOOP_THRESHOLD || alreadyNotified) return null
            buildString {
                append("🚨 检测到重试循环: 同一命令 \"${commandLine.take(100)}\" 因同一错误 [${errorCode}] ")
                append("已失败 $retryCount 次。立即停止重试, 不要重复相同操作。请三选一:\n")
                append("  ① 重新检查命令用法 (evolution.learn.command / self.tools / agent.cli)\n")
                append("  ② 换一种根本不同的方法完成任务\n")
                append("  ③ 向用户如实说明无法完成及原因\n")
            }
        } catch (_: Exception) { null }
    }

    /**
     * 会话结局真实度记录 (P0 幻觉率, 2026-08-08 自检): ReAct 循环收到 Final Answer 时调用。
     * @param failedCommands 本轮失败命令列表 (command, errorCode); 空则不计。
     * @param finalAnswer 最终回答文本 — 启发式检测是否如实提及各失败。
     * 永不抛异常。
     */
    fun recordSessionOutcome(agentName: String?, failedCommands: List<Pair<String, String>>, finalAnswer: String) {
        try {
            if (failedCommands.isEmpty()) return
            val agent = agentFileOf(agentName)
            val unmentioned = failedCommands.count { (cmd, code) ->
                !isFailureMentioned(finalAnswer, cmd, code)
            }
            ensureVeracityLoaded(agent)
            synchronized(veracityLock) {
                val (total, bad) = veracityTotals[agent] ?: (0 to 0)
                veracityTotals[agent] = (total + failedCommands.size) to (bad + unmentioned)
            }
            // 持久化: 追加一条会话记录 (失败不抛 — 统计不影响主链路)
            try {
                val file = File(DataPaths.evolutionVeracityFile(agent))
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(VeracityRecord(
                    agentName = agent,
                    totalFailures = failedCommands.size,
                    unmentionedFailures = unmentioned
                )) + "\n")
            } catch (_: Exception) { /* 持久化失败不阻塞统计 */ }
        } catch (_: Exception) { /* 统计永不崩溃 */ }
    }

    /**
     * 幻觉检测 (P0 实质化, 2026-08-08): Final Answer 是否如实提及一次失败。
     * 启发式: 含该失败错误码, 或含任一失败词 → 视为如实提及。
     * 2026-08-08 放宽: 不再要求命令名 — 静默门禁引导 Agent 自然语言汇报后,
     * 给用户的回答里几乎不会出现内部命令名 (agent.write 等), 含失败词即视为已承认失败;
     * "没有失败"式反例措辞属可接受噪声, 写操作仍有读回验证兜底, 不会让假数据闭环。
     * 供 [recordSessionOutcome] 统计 与 Final Answer 门禁共用, 单点维护。
     */
    fun isFailureMentioned(finalAnswer: String, command: String, errorCode: String): Boolean {
        val mentionedCode = errorCode.isNotBlank() && finalAnswer.contains(errorCode)
        val mentionedFailureWord = FAILURE_WORDS.any { finalAnswer.contains(it) }
        return mentionedCode || mentionedFailureWord
    }

    /**
     * Final Answer 门禁核心 (P0 实质化, 2026-08-08): 返回未被如实提及的失败列表。
     * 空列表 = 全部如实提及, 门禁放行; 非空 = 幻觉, 由 AgentReActLoop 拒绝 Final Answer
     * 并注入失败清单强制重写。纯函数可单测。
     */
    fun unmentionedFailures(
        finalAnswer: String,
        failedCommands: List<Pair<String, String>>
    ): List<Pair<String, String>> =
        failedCommands.filter { (cmd, code) -> !isFailureMentioned(finalAnswer, cmd, code) }

    /** 会话真实度摘要 (P0): 如实提及率 + 疑似幻觉提示。供 evolution.audit 展示。 */
    fun veracityStats(agentName: String?): String {
        return try {
            val agent = agentFileOf(agentName)
            ensureVeracityLoaded(agent)
            val (total, bad) = synchronized(veracityLock) { veracityTotals[agent] ?: (0 to 0) }
            if (total == 0) return "(暂无会话失败数据)"
            val honest = total - bad
            buildString {
                appendLine("会话失败如实提及: $honest/$total (未如实提及 $bad 条)")
                if (bad > 0 && total >= 3) {
                    appendLine("⚠️ 疑似幻觉风险: $bad 条失败未在最终回答中如实反映 — 结果纪律要求写操作后读回验证、失败必须原样引用错误。")
                } else if (bad > 0) {
                    appendLine("提示: $bad 条失败在最终回答中未体现 — 请确认是否已如实向用户汇报。")
                }
            }
        } catch (_: Exception) { "(统计失败)" }
    }

    /**
     * 懒加载 veracity.jsonl 历史 (进程首次访问时), 使幻觉率统计跨会话/跨进程累计。
     * 行解析失败跳过 (防损坏文件阻断统计); 读取失败降级为空统计。幂等。
     */
    private fun ensureVeracityLoaded(agent: String) {
        synchronized(veracityLock) {
            if (veracityLoaded) return
            veracityLoaded = true
            try {
                val file = File(DataPaths.evolutionVeracityFile(agent))
                if (!file.exists()) return
                file.readLines().forEach { line ->
                    try {
                        val r = json.decodeFromString<VeracityRecord>(line)
                        if (r.agentName == agent) {
                            val (total, bad) = veracityTotals[agent] ?: (0 to 0)
                            veracityTotals[agent] = (total + r.totalFailures) to (bad + r.unmentionedFailures)
                        }
                    } catch (_: Exception) { /* 跳过坏行 */ }
                }
            } catch (_: Exception) { /* 读取失败降级为空统计 */ }
        }
    }

    /** 测试隔离: 清空幻觉率内存统计与加载标志 (持久化文件不动)。 */
    internal fun resetVeracityForTest() {
        synchronized(veracityLock) {
            veracityTotals.clear()
            veracityLoaded = false
        }
    }

    /** 命令前缀命中种子 → 返回匹配的种子列表 (可能多条)。永不抛异常。 */
    fun matchSeeds(command: String): List<SeedPattern> =
        try { SEED_PATTERNS.filter { it.matches(command) } } catch (_: Exception) { emptyList() }

    // ── 复现缺陷检测 (P1-4 沉淀闭环验证) ─────────────────────────────

    /**
     * 沉淀修正后同型错误仍复发 → 自动升级为框架缺陷 (沉淀闭环失效)。
     * 触发条件: 同 agent 同命令前缀 + 同错误码出现 ≥2 次, 且存在 markCorrected=true
     * 的同前缀记录 (缓冲优先, 丢失后回退扫描 failures.jsonl)。
     * 自动写入 evolution.report 同款反馈通道 (feedback 目录 md 落盘 + NotifyBus 推送),
     * 返回落盘路径提示; 未触发或已写过返回 null。永不抛异常。
     */
    private fun detectRecurrenceDefect(agent: String, command: String, errorCode: String, count: Int): String? {
        if (count < 2) return null
        val prefix = commandPrefixOf(command)
        if (prefix.isBlank() || !hasCorrectedLesson(agent, prefix)) return null
        val key = "$agent|$prefix|$errorCode"
        if (!autoFeedbackKeys.add(key)) return null
        return try {
            val dir = File(DataPaths.evolutionFeedbackDir(agent))
            dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = File(dir, "$ts.md")
            atomicWrite(file, buildString {
                appendLine("# 框架缺陷反馈 (自动升级)")
                appendLine()
                appendLine("- 触发: 失败模式沉淀修正后仍复发")
                appendLine("- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}")
                appendLine("- Agent: $agent")
                appendLine()
                appendLine("## 问题描述")
                appendLine("命令 $command [$errorCode] 在标记已修正 (evolution.mark-corrected) 后仍复现 $count 次 — 沉淀闭环失效, 疑似框架缺陷 (命令实现/文档/引导文案问题)。")
                appendLine("请核查该命令实现、BuiltinCommandIndex 关键词与进化引导文案。")
            })
            NotifyBus.message("⚠️ Agent 发现疑似框架缺陷 (修正后仍复发): ${file.absolutePath}")
            "沉淀修正后同型错误仍复发 $count 次, 已自动升级为框架缺陷反馈, 落盘: ${file.absolutePath}"
        } catch (_: Exception) {
            // 写入失败也计入 autoFeedbackKeys — 不重试刷屏
            null
        }
    }

    /** command 前缀 — 取命名空间级前缀: "agent.memory.keep" → "agent.memory"; "fs.cat" → "fs"。 */
    private fun commandPrefixOf(command: String): String =
        command.substringBeforeLast(".", missingDelimiterValue = command)

    /** 是否存在已沉淀修正 (corrected=true) 的同前缀记录 — 缓冲优先, 回退 failures.jsonl。 */
    private fun hasCorrectedLesson(agent: String, prefix: String): Boolean {
        if (buffer.toList().any { it.agentName == agent && it.corrected && it.command.startsWith(prefix) }) return true
        return try {
            val file = failuresFile(agent)
            if (!file.exists()) return false
            file.readLines().any { line ->
                try {
                    json.decodeFromString<EvolutionFailure>(line)
                        .let { it.corrected && it.command.startsWith(prefix) }
                } catch (_: Exception) { false }
            }
        } catch (_: Exception) { false }
    }

    /**
     * 将一条失败模式标记为已沉淀修正(Agent 已把教训写入记忆/技能)。
     * 供 evolution.mark-corrected 命令使用。
     */
    fun markCorrected(agentName: String?, failureId: String): Boolean {
        return try {
            val agent = agentFileOf(agentName)
            val updated = buffer.find { it.id == failureId && it.agentName == agent }?.copy(corrected = true)
            if (updated != null) {
                val list = buffer.toList().map { if (it.id == failureId) updated else it }
                buffer.clear(); buffer.addAll(list)
                rewriteFile(agent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    // ── 用户反应 ────────────────────────────────────────────────────

    /**
     * 记录一条用户反应(纠正/撤回)。追加到 reactions.md。
     * contextSnippet 为上下文切片(上一条 Agent 回复摘要)。
     */
    fun recordCorrection(agentName: String?, correction: String, contextSnippet: String, task: String) {
        try {
            val agent = agentFileOf(agentName)
            val file = reactionsFile(agent)
            file.parentFile?.mkdirs()
            val block = buildString {
                appendLine()
                appendLine("## ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                appendLine("- 纠正: ${correction.take(200)}")
                appendLine("- 上下文: ${contextSnippet.take(400)}")
                appendLine("- 任务: ${task.take(300)}")
            }
            atomicAppend(file, block + "\n")
        } catch (_: Exception) { /* 存储必须永不崩溃 */ }
    }

    // ── 查询 ────────────────────────────────────────────────────────

    /** 最近 n 条失败记录(新→旧)。agentName 为空时返回全部。 */
    fun recentFailures(agentName: String?, n: Int = 20): List<EvolutionFailure> =
        try {
            val agent = agentFileOf(agentName)
            buffer.toList().filter { agentName == null || it.agentName == agent }.takeLast(n).reversed()
        } catch (_: Exception) { emptyList() }

    /** 复现模式 Top N(同模式出现 ≥2 次), 按次数降序。 */
    fun repeatedPatterns(agentName: String?, n: Int = 10): List<EvolutionFailure> =
        try {
            val agent = agentFileOf(agentName)
            buffer.toList()
                .filter { it.agentName == agent }
                .groupBy { "${it.command}|${it.errorCode}" }
                .map { (_, list) -> list.maxBy { it.repeatCount } }
                .filter { it.repeatCount >= 2 }
                .sortedByDescending { it.repeatCount }
                .take(n)
        } catch (_: Exception) { emptyList() }

    /** 用户反应档案全文(供 Agent L3 提问与 evolution.reactions 查看)。 */
    fun reactionsText(agentName: String?): String {
        return try {
            val file = reactionsFile(agentFileOf(agentName))
            if (file.exists()) file.readText() else "(暂无用户纠正记录)"
        } catch (_: Exception) {
            "(读取失败)"
        }
    }

    /** 绩效摘要 — 供 evolution.audit 与引导注入。 */
    fun stats(agentName: String?): String {
        return try {
            val all = buffer.toList().filter { it.agentName == agentFileOf(agentName) }
            val repeated = all.filter { it.repeatCount >= 2 }
            val corrected = all.count { it.corrected }
            buildString {
                appendLine("进化绩效 (${agentFileOf(agentName)})")
                appendLine("记录失败: ${all.size}")
                appendLine("复现模式: ${repeated.size} 种")
                appendLine("已沉淀修正: $corrected")
                if (all.isNotEmpty() && corrected == 0) {
                    appendLine("⚠️ 红灯: 有 ${all.size} 条失败但 0 条已沉淀 — 闭环未完成, 请当场用 agent.memory.keep / evolution.learn.command 处理 (见下方「下一步可用动作」)")
                }
                if (repeated.isNotEmpty()) {
                    appendLine()
                    appendLine("### 复现模式")
                    repeated.take(5).forEach { f ->
                        appendLine("- ${f.command} [${f.errorCode}] ×${f.repeatCount}${if (f.corrected) " ✅已修正" else ""}")
                    }
                }
                appendLine()
                appendLine("### 常见错误预防清单 (内置预防种子 — 非真实失败记录)")
                appendLine("新手常见错误预防, 命中对应命令前缀时失败记录自动附教训提示:")
                SEED_PATTERNS.forEach { s ->
                    appendLine("- 种子#${s.id} [${s.prefixes.joinToString("/")}]: ${s.description} → ${s.lesson}")
                }
                appendLine()
                if (repeated.isNotEmpty() || corrected < all.size) {
                    appendLine("### 下一步可用动作 (闭环未完成: 复现 ${repeated.size} 种 / 未沉淀 ${all.size - corrected} 条)")
                    appendLine("- 沉淀本次会话教训: agent.memory.keep <内容> 或 agent.memory.project.save <项目> <总结>")
                    appendLine("- 登记命令正确用法: evolution.learn.command <命令> <描述> [--keywords 词1,词2]")
                    appendLine("- 标记失败已修正: evolution.mark-corrected <失败id> (id 见上方复现模式)")
                    appendLine("- 上报框架缺陷: evolution.report <描述>")
                }
                appendLine()
                appendLine("### 会话幻觉率 (P0 结果可信度)")
                appendLine(veracityStats(agentName))
            }
        } catch (_: Exception) {
            "(统计失败)"
        }
    }

    // ── 旧版数据迁移 (v0.34.x) ─────────────────────────────────────

    /**
     * 一次性迁移: 旧版无主进化档案写在 `{AGENTS}/default/evolution/`, 被 Agent
     * 发现逻辑 (目录扫描) 误判为假 Agent, 且一旦被识别, 会话 bootstrap 会把
     * cli.md/soul.md 等模板写进 default/ — 越看越像真 Agent (自我强化)。
     *
     * 迁移动作:
     * 1. `{AGENTS}/default/evolution/` 下全部内容 → `{BASE}/进化档案/` (不覆盖已有新数据)。
     * 2. 删除 `{AGENTS}/default/` 下其余内容 (误生成的模板) 与目录本身。
     *
     * 幂等 (无旧数据时零开销); 永不抛异常。应用启动时调用。
     */
    fun migrateLegacyDefaultDir() {
        try {
            val legacy = File(DataPaths.AGENTS, DEFAULT_AGENT)
            if (!legacy.isDirectory) return
            val legacyEvolution = File(legacy, "evolution")
            if (legacyEvolution.isDirectory) {
                val target = File(DataPaths.EVOLUTION)
                target.mkdirs()
                legacyEvolution.listFiles()?.forEach { moveIfAbsent(it, File(target, it.name)) }
            }
            legacy.listFiles()?.forEach { it.deleteRecursively() }
            legacy.delete()
        } catch (_: Exception) { /* 迁移永不崩溃 */ }
    }

    /** 递归移动 (目标已存在 → 跳过保留新数据)。 */
    private fun moveIfAbsent(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { moveIfAbsent(it, File(dest, it.name)) }
            src.delete()
        } else if (!dest.exists()) {
            src.renameTo(dest)
        } else {
            src.delete()
        }
    }

    // ── 文件 ────────────────────────────────────────────────────────

    /** 规范化 agent 名 — 无主 (null/空白) 归保留字 "default" (buffer 标识);
     *  文件路径映射见 [DataPaths.evolutionDir]: "default" → {BASE}/进化档案/。 */
    private fun agentFileOf(agentName: String?): String =
        agentName?.replace(Regex("[/\\\\]"), "_")?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT

    private fun failuresFile(agent: String): File = File(DataPaths.evolutionFailuresFile(agent))
    private fun reactionsFile(agent: String): File = File(DataPaths.evolutionReactionsFile(agent))

    private fun appendToFile(agent: String, entry: EvolutionFailure) {
        try {
            val file = failuresFile(agent)
            file.parentFile?.mkdirs()
            atomicAppend(file, json.encodeToString(entry) + "\n")
        } catch (_: Exception) { }
    }

    /** 重写整个 jsonl(用于 corrected 状态落盘)。 */
    private fun rewriteFile(agent: String) {
        try {
            val file = failuresFile(agent)
            file.parentFile?.mkdirs()
            val lines = buffer.toList().filter { it.agentName == agent }
            atomicWrite(file, lines.joinToString("\n") { json.encodeToString(it) } + if (lines.isEmpty()) "" else "\n")
        } catch (_: Exception) { }
    }

    // ── 原子写入 (tmp + rename, 防崩溃损坏 — 全项目写入铁律) ───────

    /** 原子追加: 读原内容 → tmp 全量写 → rename 替换。 */
    private fun atomicAppend(file: File, content: String) {
        val old = if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
        atomicWrite(file, old + content)
    }

    /** 原子写入: tmp + Files.move(REPLACE_EXISTING) 覆盖, 失败保留原文件并清理 tmp。 */
    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(content)
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            throw e
        }
    }
}
