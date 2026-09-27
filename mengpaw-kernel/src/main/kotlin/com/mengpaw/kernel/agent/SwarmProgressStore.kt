// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * SWARM 看板进度持久化 — 与 GOAL/Ralph 同目录 ([GoalSessionStore.progressDir]) 的**第二个档**,
 * 而非第二套状态源: 目录、原子写策略、fail-soft 读语义与 [GoalSessionStore] 完全一致。
 *
 * 落盘位置: `{DataPaths.CONFIG}/模式进度/{agent}/swarm.json`
 *
 * 为什么 SWARM 单独成档见 [SwarmProgress] 的 KDoc (看板粒度 vs 扁平会话状态)。
 *
 * 读写纪律:
 * - 写: 原子写 (同名 `.tmp` + `Files.move(REPLACE_EXISTING)`), 全程 try/catch + `synchronized`
 *   (并行 worker 会并发快照, 同 tmp 文件必须串行化);
 * - 读: 文件不存在 / 空 / JSON 损坏 → null (视为无进度), **永不抛异常**;
 * - 旧档兼容: `ignoreUnknownKeys = true` + 新增字段带默认值, 缺字段照常解码。
 */
object SwarmProgressStore {

    private const val FILE_NAME = "swarm.json"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        prettyPrint = true
    }

    /** 恢复入口用文件路径 (签名冻结)。 */
    fun progressFile(agentName: String): String =
        "${GoalSessionStore.progressDir(agentName)}/$FILE_NAME"

    /**
     * 读取 [agentName] 的 SWARM 看板进度; 无进度 / 损坏 → null。
     *
     * 恢复语义: 返回对象的 [SwarmProgress.consumedSteps] 即"重启前已烧掉的步数",
     * 恢复预算时必须以它续接 (剩余 = 总额 − 已消耗), 否则重启即预算翻倍。
     */
    fun loadForResume(agentName: String): SwarmProgress? = loadFrom(File(progressFile(agentName)))

    /** 落盘 [progress] (原子写, 失败静默)。 */
    fun saveProgress(agentName: String, progress: SwarmProgress) {
        saveTo(progress, File(progressFile(agentName)))
    }

    /** 清除 [agentName] 的 SWARM 进度存档 (任务终态后调用)。 */
    fun clear(agentName: String) {
        try { File(progressFile(agentName)).delete() } catch (_: Exception) {}
    }

    // ── 显式文件形态 (执行器已持有 sessionFile 时用, 便于测试注入临时目录) ──

    /** 从显式文件加载; 不存在 / 空 / 损坏 → null, 永不抛异常。 */
    fun loadFrom(file: File): SwarmProgress? {
        return try {
            if (!file.exists()) return null
            val text = file.readText()
            if (text.isBlank()) null else json.decodeFromString(SwarmProgress.serializer(), text)
        } catch (_: Exception) {
            null
        }
    }

    /** 原子写显式文件; 失败静默。 */
    fun saveTo(progress: SwarmProgress, file: File) {
        synchronized(this) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(SwarmProgress.serializer(), progress))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // 进度快照失败不阻塞火种执行
            }
        }
    }

    /** 清理显式文件。 */
    fun clearFile(file: File) {
        try { file.delete() } catch (_: Exception) {}
    }
}
