// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.mengpaw.harness.CheckpointStatus
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector

/**
 * 会话检查点持久化 — 保存/恢复 Agent 进度 (断点续跑)。
 *
 * 全部 IO 走 [DataPaths.fs] (HarnessFileSystem) 与 [storageDir] 目录, 不再直连
 * `java.io.File`; `withContext(Dispatchers.IO)` 避免阻塞主线程。
 *
 * ## 文件命名与定位规则 (本次升级的核心)
 * - 新格式: `{sessionId 消毒后}__step_{step}.json`, **双下划线分隔**。
 *   旧格式裸拼 `_step_`, sessionId 尾部的 `_step_N` 段与分隔符无法区分;
 *   双下划线让"分隔符"与"id 内下划线"可辨 (旧格式 `{sessionId}_step_{n}.json` 仍可读, 只读不写)。
 * - 读取 ([loadLatest] / [loadLatestSync]): 先按**文件名精确匹配**定位候选, 再以
 *   **JSON 内 sessionId 字段为准**判归属 — 文件名只做定位, 消毒是有损映射
 *   (`a.b` 与 `a_b` 同形), 逆推不可行, 故不逆推。精确匹配同时消灭了旧实现的
 *   `name.startsWith(sessionId)` 前缀歧义 (sessionId `"a"` 不再误读 `"ab"` 的档)。
 * - [listSessions] 同理: 文件名定位 + JSON 内 sessionId 还原, 档损坏时以文件名主体兜底。
 *
 * ## 原子性
 * [com.mengpaw.harness.HarnessFileSystem] 没有 move/rename 语义, 故采用
 * **"先写新档 (writeText 覆盖) → 再删旧档"** 的顺序: 任何时刻至少有一个完整档可读;
 * 最坏情况是崩在删旧档之前 → 多留几份旧档, 由 [cleanup] 下次收口 (绝不出现"无档可读")。
 *
 * ## 上下文可控 (为什么要截断)
 * `messages` 只保留恢复所需字段 (role/content/reasoning, 见 [Message]);
 * 落盘前单条 content 截断至 [MAX_CONTENT_CHARS]、最多保留最近 [MAX_MESSAGES] 条。
 * 取值理由: 恢复只需要"最近一段可续跑的上下文", 完整历史在本会话归档里有底;
 * 8000 字符约 2-4K token, 200 条上限把单档钉在 ~1.6M 字符量级 — 既避免"一分钟一步"的
 * 长任务把检查点撑成几十 MB (每次落盘全量重写 → IO 放大), 又足够覆盖任何单步工具观察结果。
 * **凭据 (API Key 等) 严禁落盘**: [Message] 内容只来自对话与工具观察, 密钥从不进入消息体;
 * 本类也不接收任何凭据入参, 截断逻辑不做"看似要保存全部"的兜底。
 *
 * ## 向后兼容
 * 旧格式档 (无 status/messages/updatedAt) 仍可读: 缺失字段走 [Checkpoint] 默认值
 * (status = RUNNING, updatedAt 回落 createdAt)。读不到 / 损坏 / 非本会话 → 一律视作
 * "无检查点"返回 null, 不抛异常。
 */
class CheckpointManager(private val storageDir: String = DataPaths.CHECKPOINTS) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * 保存检查点(异步)。失败静默上报 [ErrorCollector], 不抛给调用方 — 检查点是可靠性
     * 增强, 不是新的失败点 (与 harness 侧 ReActEngine 的语义一致)。
     *
     * 落盘顺序: 写新档 → 删同会话的旧档 (最多留 [DEFAULT_KEEP_COUNT] 份)。
     */
    suspend fun save(checkpoint: Checkpoint) = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val sanitized = sanitizeSessionId(checkpoint.sessionId)
            val payload = truncateForStorage(checkpoint)
            val content = json.encodeToString(payload)
            val bytes = content.toByteArray(Charsets.UTF_8).size

            // 先写新档(同名覆盖), 成功后才动旧档 — 保证任意时刻至少有一个完整档
            DataPaths.fs.writeText(
                path = "$storageDir/${fileNameFor(sanitized, checkpoint.step)}",
                content = content,
                createParentDirs = true
            )
            CheckpointMetrics.recordSave()
            CheckpointMetrics.recordSaveMillis(System.currentTimeMillis() - started)
            CheckpointMetrics.recordSavedShape(payload.messages.size, bytes.toLong())
            deleteSuperseded(sanitized, checkpoint.step)
        } catch (e: Exception) {
            CheckpointMetrics.recordSaveFail()
            ErrorCollector.report(e, "CheckpointManager.save")
        }
    }

    /**
     * 读取某会话最近一次检查点(异步)。
     * @return 无检查点 / 档损坏返回 null (不抛异常)。
     */
    suspend fun loadLatest(sessionId: String): Checkpoint? = withContext(Dispatchers.IO) {
        loadLatestSync(sessionId)
    }

    /**
     * [loadLatest] 的同步版本 — 供初始化/进程死亡恢复等无协程上下文的路径使用
     * (如 AgentRuntime.restoreCurrentSession)。
     */
    fun loadLatestSync(sessionId: String): Checkpoint? {
        val candidates = try {
            collectFiles(sessionId)
        } catch (e: Exception) {
            CheckpointMetrics.recordLoadFail()
            ErrorCollector.report(e, "CheckpointManager.loadLatest")
            return null
        }
        if (candidates.isEmpty()) {
            CheckpointMetrics.recordLoadMiss()
            return null
        }
        val decodable = candidates.mapNotNull { decode(it) }
        if (decodable.isEmpty()) {
            // 档存在但全部不可解析 = 视作无检查点 (fail-soft), 不抛异常
            CheckpointMetrics.recordLoadMiss()
            return null
        }
        val best = decodable.maxWithOrNull(
            compareBy({ it.decoded?.updatedAt ?: 0L }, { it.step }, { it.modified })
        )
        val result = best?.decoded
        if (result == null) CheckpointMetrics.recordLoadMiss() else CheckpointMetrics.recordLoadHit()
        return result
    }

    /**
     * 保留某会话最近 [keep] 份检查点, 删除更旧的。
     *
     * 排序主判据是档内 `updatedAt` / `step` 而非 `lastModified` — 文件系统时间戳精度有限,
     * 同一秒内多次写入无法区分 (旧实现按 lastModified 排序在快速连续落盘时会留错档)。
     * 目录不存在 / 无匹配档 / [keep] ≤ 0 → 无操作。
     */
    suspend fun cleanup(sessionId: String, keep: Int = DEFAULT_KEEP_COUNT) =
        withContext(Dispatchers.IO) {
            try {
                val safeKeep = keep.coerceAtLeast(0)
                val files = scanBySanitized(sanitizeSessionId(sessionId))
                if (files.size <= safeKeep) return@withContext
                files.sortedWith(STALEST_FIRST).dropLast(safeKeep).forEach(::deleteQuietly)
            } catch (e: Exception) {
                ErrorCollector.report(e, "CheckpointManager.cleanup")
            }
        }

    /**
     * 清除某会话的全部检查点。
     *
     * 实现为"写墓碑档 → 再删旧档"而非直接删: 抽象层没有 move, 直接删会在读侧留下
     * "旧档还在 → 读回已失效进度"的窗口; 墓碑档 (step=-1, status=FAILED) 保证清除后
     * [loadLatest] 立即读不到可续跑进度, 且不违反"至少一个完整档"。
     */
    suspend fun clear(sessionId: String) = withContext(Dispatchers.IO) {
        try {
            val sanitized = sanitizeSessionId(sessionId)
            val tombstone = Checkpoint(
                sessionId = sessionId,
                step = CLEARED_STEP,
                remainingTask = "",
                context = emptyMap(),
                status = CheckpointStatus.FAILED,
                terminationReason = CLEARED_REASON
            )
            DataPaths.fs.writeText(
                path = "$storageDir/${fileNameFor(sanitized, CLEARED_STEP)}",
                content = json.encodeToString(tombstone),
                createParentDirs = true
            )
            deleteSuperseded(sanitized, CLEARED_STEP)
        } catch (e: Exception) {
            ErrorCollector.report(e, "CheckpointManager.clear")
        }
    }

    /**
     * 已知会话 id 列表 (去重排序)。
     *
     * 还原规则: 优先取档内 JSON 的 `sessionId` 字段 (权威); 解析失败才用文件名主体兜底 —
     * 消毒有损, 兜底值可能与真实 id 不同, 仅用于展示/巡检, **不可用于再拼路径**。
     */
    fun listSessions(): List<String> = try {
        DataPaths.fs.listFiltered(storageDir, suffix = FILE_SUFFIX)
            .mapNotNull { name ->
                if (name.endsWith(TMP_SUFFIX)) return@mapNotNull null
                val parsed = parseStepFile(name.removeSuffix(FILE_SUFFIX)) ?: return@mapNotNull null
                readCheckpoint("$storageDir/$name")?.sessionId ?: parsed.key
            }
            .distinct()
            .sorted()
    } catch (e: Exception) {
        ErrorCollector.report(e, "CheckpointManager.listSessions")
        emptyList()
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    /** 收集属于 [sessionId] 的候选档 (文件名精确定位 + JSON 内 sessionId 判归属)。 */
    private fun collectFiles(sessionId: String): List<CheckpointFile> {
        val sanitized = sanitizeSessionId(sessionId)
        return scanBySanitized(sanitized).mapNotNull { decode(it) }
            .filter { it.decoded?.sessionId == sessionId }
    }

    /** 按消毒名收集全部候选档 (不做 JSON 归属过滤 — 删除路径只信文件名定位)。 */
    private fun scanBySanitized(sanitized: String): List<CheckpointFile> =
        DataPaths.fs.listFiltered(storageDir, suffix = FILE_SUFFIX)
            .asSequence()
            .filterNot { it.endsWith(TMP_SUFFIX) }
            .mapNotNull { name ->
                val parsed = parseStepFile(name.removeSuffix(FILE_SUFFIX))
                if (parsed?.key != sanitized) return@mapNotNull null
                val path = "$storageDir/$name"
                CheckpointFile(path, parsed.step, DataPaths.fs.lastModified(path))
            }
            .toList()

    /** 单档解析 + 时间戳回填, 解析失败返回 null。 */
    private fun decode(file: CheckpointFile): CheckpointFile? {
        if (file.decoded != null) return file
        val parsed = readCheckpoint(file.path) ?: return null
        return file.copy(decoded = parsed)
    }

    /** 单档读取, 任何异常 (不存在/不可读/JSON 损坏) 一律返回 null。 */
    private fun readCheckpoint(path: String): Checkpoint? = try {
        json.decodeFromString<Checkpoint>(DataPaths.fs.readText(path))
    } catch (e: Exception) {
        null
    }

    /** 删除同会话除 [keepStep] 之外的旧档, 最多再留 [DEFAULT_KEEP_COUNT] 份 (写新档之后调用)。 */
    private fun deleteSuperseded(sanitized: String, keepStep: Int) {
        val stale = scanBySanitized(sanitized).filter { it.step != keepStep }
        if (stale.size <= DEFAULT_KEEP_COUNT) return
        stale.sortedWith(STALEST_FIRST).dropLast(DEFAULT_KEEP_COUNT).forEach(::deleteQuietly)
    }

    private fun deleteQuietly(file: CheckpointFile) {
        try {
            DataPaths.fs.delete(file.path)
        } catch (e: Exception) {
            ErrorCollector.report(e, "CheckpointManager.delete")
        }
    }

    /** 落盘前截断: 只留恢复所需字段 + 限制条数与单条长度 (取值理由见类 KDoc)。 */
    private fun truncateForStorage(checkpoint: Checkpoint): Checkpoint {
        if (checkpoint.messages.isEmpty()) return checkpoint
        val capped = checkpoint.messages.takeLast(MAX_MESSAGES).map { message ->
            // 只保留 role/content/reasoning 语义: 附件/本地标记/中断恢复元数据不参与续跑,
            // 落盘即丢 (减体积, 也避免把只读元数据写回磁盘造成二次污染)
            message.copy(
                content = message.content.take(MAX_CONTENT_CHARS),
                localOnly = false,
                interruptedTurn = null,
                attachments = emptyList()
            )
        }
        return checkpoint.copy(messages = capped)
    }

    private companion object {
        /** 默认保留份数 (与旧实现的 keep=3 一致, 行为向后兼容)。 */
        const val DEFAULT_KEEP_COUNT = 3

        /** 单条 content 落盘上限 (字符) — 约 2-4K token, 足够恢复一步上下文。 */
        const val MAX_CONTENT_CHARS = 8000

        /**
         * messages 条数上限 — 只保留最近 200 条。
         * 理由: 续跑需要"最近的连续上下文", 更早历史在会话归档里有底; 上限把单档体积钉住,
         * 避免长任务每次落盘重写整段历史造成 IO 放大。
         */
        const val MAX_MESSAGES = 200

        const val TMP_SUFFIX = ".tmp"
        const val CLEARED_STEP = -1
        const val CLEARED_REASON = "cleared"

        /** 最旧优先 (删除时从头部取)。 */
        val STALEST_FIRST: Comparator<CheckpointFile> = compareBy<CheckpointFile>(
            { it.decoded?.updatedAt ?: 0L }, { it.step }, { it.modified }
        )
    }
}

/** 候选档: 路径 / 解析出的步数 / 文件系统时间戳 / 解析结果 (解析失败为 null)。 */
private data class CheckpointFile(
    val path: String,
    val step: Int,
    val modified: Long,
    val decoded: Checkpoint? = null
)

/**
 * 文件名主体 → 结构化定位信息。
 *
 * 精确匹配规则: 主体必须形如 `{会话键}__step_{数字}` (新格式) 或 `{会话键}_step_{数字}` (旧格式)。
 * 优先按双下划线切分 — 否则 `id__step_3` 会被单下划线规则切成 `id_` (前缀歧义的另一副面孔)。
 * 模式用 `.+` 前缀锚定两端: 键自带 `_step_` 时从右侧切分, 仍能切对。
 * 返回 null = 文件名不是检查点档 (如别的工具写进同目录的 JSON), 直接跳过。
 */
private fun parseStepFile(stem: String): StepFile? {
    val newFormat = NEW_FORMAT.find(stem)
    if (newFormat != null) {
        val key = newFormat.groupValues[1]
        val step = newFormat.groupValues[2].toIntOrNull()
        return if (key.isNotBlank() && step != null) StepFile(key, step) else null
    }
    val legacy = LEGACY_FORMAT.find(stem) ?: return null
    val legacyKey = legacy.groupValues[1]
    val legacyStep = legacy.groupValues[2].toIntOrNull() ?: return null
    return if (legacyKey.isNotBlank()) StepFile(legacyKey, legacyStep) else null
}

/**
 * sessionId 消毒 — 只保留 `[A-Za-z0-9_]` 与中日韩文字, 其余 (含 `.` `/` `\` 空格) 替换为 `_`。
 *
 * 不复用 harness 的消毒函数: [com.mengpaw.harness.sanitizeCheckpointId] 会把中文也替换掉
 * (MengPaw 会话 id 含中文, 替换后不可读); [com.mengpaw.harness.HarnessPathResolver.sanitizeSegment]
 * 又太松 (只换分隔符, 点号与 `..` 会存活 → 路径穿越风险)。故此处自行消毒, 规则更严更贴合本仓。
 */
private fun sanitizeSessionId(raw: String): String {
    val cleaned = raw.trim().replace(UNSAFE_ID_CHARS, "_")
    return cleaned.ifBlank { "session" }.take(SESSION_ID_MAX_LEN)
}

/** 文件名 = `{消毒 id}__step_{step}.json` — 双下划线分隔, 与 id 内下划线可辨。 */
private fun fileNameFor(sanitized: String, step: Int): String =
    "${sanitized.take(SESSION_ID_MAX_LEN)}$STEP_SEPARATOR$step$FILE_SUFFIX"

/** 解析结果: 消毒后会话键 + 步数。 */
private data class StepFile(val key: String, val step: Int)

/** 新格式: `{键}__step_{数字}`。 */
private val NEW_FORMAT = Regex("""^(.+)__step_(\d+)$""")

/** 旧格式 (只读兼容): `{键}_step_{数字}`。 */
private val LEGACY_FORMAT = Regex("""^(.+)_step_(\d+)$""")

/** 文件名中的不安全字符 (含 `.` — 于是 `..` 无法存活)。 */
private val UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9_\\u4e00-\\u9fff]")

/** 分隔符与扩展名 (文件级常量, 供顶层函数共享)。 */
private const val STEP_SEPARATOR = "__step_"
private const val FILE_SUFFIX = ".json"

/** 消毒后的 sessionId 最大长度 (给分隔符/步数/扩展名留足余量)。 */
private const val SESSION_ID_MAX_LEN = 96
