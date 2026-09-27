// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Goal 会话持久化存储 (P2-4) — 把 [GoalSession] 落盘 JSON, 支持跨会话续跑。
 *
 * 背景: GoalModeExecutor 原为纯内存回合循环, 任务中断 (进程被杀/用户离开) 后无法恢复,
 * 只能整轮重跑。本类把会话状态 (goal/iteration/tokensUsed/verdict/feedback) 序列化到
 * 指定文件, 后续可 [load] 回续跑 (DSH goal-round-driver 同目标续跑思想的轻量实现)。
 *
 * 文件 IO 全程 try/catch — 持久化失败不阻塞主流程 (进化记录同类约定)。
 *
 * ── 多模式进度 (工作流 C) ────────────────────────────────────────────
 * 除单文件 API ([save]/[load]/[clear]) 外, 本对象同时是 GOAL/Ralph/FLEET 的
 * **按 Agent 命名的进度主库**, 目录与 Swarm 看板同源:
 *
 * ```
 * {DataPaths.CONFIG}/模式进度/{agent}/goal.json    ← GOAL / FLEET 会话进度
 * {DataPaths.CONFIG}/模式进度/{agent}/ralph.json   ← Ralph 轮次与累计预算
 * {DataPaths.CONFIG}/模式进度/{agent}/swarm.json   ← SWARM 看板 (见 SwarmProgressStore)
 * ```
 *
 * 只存在这一套进度落盘 (没有"两个真相"): 恢复入口 [loadForResume] / [saveProgress]
 * 与执行器内部用的单文件 API 读写同一份 JSON 结构, 原子写 (tmp + `Files.move`)。
 *
 * 读侧 fail-soft: 文件不存在 / 空 / JSON 损坏一律返回 null (视为无进度, 从不抛异常)。
 */
object GoalSessionStore {

    /** 进度根目录名 (置于 [DataPaths.CONFIG] 下, 与 swarm_runtime.json 等运行时文件同级)。 */
    private const val PROGRESS_DIR = "模式进度"

    private val json = Json {
        ignoreUnknownKeys = true
        // 容忍未来字段变更, 旧存档仍可读
        coerceInputValues = true
    }

    /** 进度根目录 — 每次访问重新解析, 保证测试里 `DataPaths.initialize` 之后立即生效。 */
    val progressRoot: String get() = "${DataPaths.CONFIG}/$PROGRESS_DIR"

    /** Agent 进度目录 — [agentName] 先消毒 (防路径穿越), 空/非法回落 `_default`。 */
    fun progressDir(agentName: String): String = "$progressRoot/${sanitize(agentName)}"

    /**
     * 进度文件路径 — 恢复入口的路径单一事实源。
     * @param mode 见 [GoalSession.MODE_GOAL] / [GoalSession.MODE_RALPH] / [GoalSession.MODE_FLEET]
     */
    fun progressFile(agentName: String, mode: String): String {
        val safeMode = MODE_FILE_NAMES[mode] ?: MODE_FILE_NAMES.getValue(GoalSession.MODE_GOAL)
        return "${progressDir(agentName)}/$safeMode"
    }

    /** 保存会话到文件。失败静默 (不抛异常)。 */
    fun save(session: GoalSession, file: File) {
        atomicWrite(file, json.encodeToString(GoalSession.serializer(), session))
    }

    /** 从文件加载会话; 文件不存在或损坏返回 null。永不抛异常。 */
    fun load(file: File): GoalSession? {
        return try {
            if (!file.exists()) return null
            val text = file.readText()
            if (text.isBlank()) null else json.decodeFromString(GoalSession.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 清空已持久化的会话文件。 */
    fun clear(file: File) {
        try { file.delete() } catch (_: Exception) {}
    }

    // ── 恢复入口 (供工作流 E 调用, 签名冻结) ──────────────────────────

    /**
     * 读取 [agentName] 的 GOAL/FLEET 会话进度; 无进度 / 旧档损坏 → null。
     *
     * 合并语义 (供调用方决定是否续跑): 返回的 [GoalSession] 中 `iteration` 为已完成轮次、
     * `tokensUsed` 为已消耗预算, 续跑时**不重置**这两者, 故剩余预算 = 总额 − 已消耗。
     */
    fun loadForResume(agentName: String): GoalSession? =
        load(File(progressFile(agentName, GoalSession.MODE_GOAL)))

    /** 落盘 [session] 的进度 (原子写)。mode 由 `session.mode` 决定文件, 失败静默。 */
    fun saveProgress(session: GoalSession) {
        save(session, File(progressFile(session.agentName, session.mode)))
    }

    /** 清除 [agentName] 的进度存档 (任务终态后调用, 防误续跑已终结会话)。 */
    fun clearForResume(agentName: String, mode: String = GoalSession.MODE_GOAL) {
        clear(File(progressFile(agentName, mode)))
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /** 原子写: 先写同名 `.tmp`, 再 `Files.move(REPLACE_EXISTING)` 替换; 任何异常不上抛。 */
    private fun atomicWrite(file: File, content: String) {
        synchronized(this) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(content)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // 持久化失败不阻塞目标执行
            }
        }
    }

    /** 文件系统安全化: 抹掉分隔符/穿越段与空字节, 空/全非法 → `_default`。 */
    private fun sanitize(agentName: String): String {
        val cleaned = agentName
            .replace('\u0000', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .replace("..", "_")
            .trim()
            .trim('.')
        return if (cleaned.isBlank()) "_default" else cleaned.take(80)
    }

    private val MODE_FILE_NAMES = mapOf(
        GoalSession.MODE_GOAL to "goal.json",
        GoalSession.MODE_RALPH to "ralph.json",
        GoalSession.MODE_FLEET to "fleet.json"
    )
}
