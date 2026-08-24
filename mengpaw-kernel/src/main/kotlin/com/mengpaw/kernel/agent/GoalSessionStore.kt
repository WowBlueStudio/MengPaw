// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Goal 会话持久化存储 (P2-4) — 把 [GoalSession] 落盘 JSON, 支持跨会话续跑。
 *
 * 背景: GoalModeExecutor 原为纯内存回合循环, 任务中断 (进程被杀/用户离开) 后无法恢复,
 * 只能整轮重跑。本类把会话状态 (goal/iteration/tokensUsed/verdict/feedback) 序列化到
 * 指定文件, 后续可 [load] 回续跑 (DSH goal-round-driver 同目标续跑思想的轻量实现)。
 *
 * 文件 IO 全程 try/catch — 持久化失败不阻塞主流程 (进化记录同类约定)。
 */
object GoalSessionStore {

    private val json = Json {
        ignoreUnknownKeys = true
        // 容忍未来字段变更, 旧存档仍可读
        coerceInputValues = true
    }

    /** 保存会话到文件。失败静默 (不抛异常)。 */
    fun save(session: GoalSession, file: File) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(GoalSession.serializer(), session))
        } catch (_: Exception) {
            // 持久化失败不阻塞目标执行
        }
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
}
