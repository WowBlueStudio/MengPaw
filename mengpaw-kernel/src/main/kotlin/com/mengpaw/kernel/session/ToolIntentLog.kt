// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.security.Sanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * 工具意图状态。
 *
 * - [PENDING]: 已声明**将要执行**但结果未知 — 崩溃窗口 (工具已产生副作用、完成记录未落盘)
 *   的唯一危险态, 恢复方据此判定"这一步可能已经执行过"。
 * - [DONE] / [FAILED]: 执行完成且成功 / 失败 (失败含门禁拒绝、超时、黑名单拦截)。
 */
enum class IntentState { PENDING, DONE, FAILED }

/**
 * 单条工具调用意图记录 (幂等锚点)。
 *
 * @property sessionId 所属会话。
 * @property step ReAct 步序号 (0 基, 与 `Checkpoint.step` 同坐标系); finish 找不到 begin 基线时兜底为
 *   `-1` (标记"基线缺失", 不影响 toolCallId 判重)。
 * @property toolCallId 稳定可复现的调用标识 — 见 [ToolIntentLog.toolCallIdOf]。
 * @property toolName 工具/命令名 (命令行首个 token)。
 * @property argsDigest **参数文本的 SHA-256 hex 前 16 位** — 严禁落参数原文 (可能含 API Key 等凭据)。
 * @property outcomeDigest 结果文本摘要 (同 [argsDigest] 算法); 成功且结果空白时为 null。
 * @property error 失败原因; 落盘前经 [ToolIntentLog.scrubError] + [Sanitizer.sanitize] 剔除原文并脱敏截断。
 */
@Serializable
data class ToolIntent(
    val sessionId: String,
    val step: Int,
    val toolCallId: String,
    val toolName: String,
    val argsDigest: String,
    val state: IntentState,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val outcomeDigest: String? = null,
    val error: String? = null
)

/**
 * 工具副作用意图日志 (Write-Ahead Intent Log) — **WAL 语义层**: 幂等索引、恢复判重、周期压实策略与
 * 终态清理时机。落盘字节与目录细节 (JSONL 追加/折叠读/原子重写/删除/文件名消毒/半行修复) 与存储格式
 * 说明都在 [ToolIntentStore] — 换存储格式不该动 WAL 策略, 反之亦然。
 *
 * **为什么需要**: 工具执行**之后**才落检查点 — 崩溃若落在"工具已执行、完成记录未落盘"之间, 恢复重放
 * 会**重复产生副作用** (重复写文件/发消息/下单)。本日志把"将要执行"的意图在**执行之前**落盘并 `fsync`
 * (见 [ToolIntentStore.append] 的 `durable`), 使恢复方能识别出"上次未完成"的调用; 检查点自 v0.48.1 起
 * 每步落一次, 但仍晚于工具执行, 故 WAL 的必要性不因此消失。
 *
 * **并发**: 复用 [ToolIntentStore.ioLock] 单锁保护文件操作与内存索引; `executeActions` 并行执行一批
 * 工具, 故 [begin]/[finish] 会被并发调用 — 线程安全, 且**不串行化工具执行本身**。
 *
 * **同一 toolCallId 重复 begin (定案: 保留既有状态)**: [begin] **幂等** — 首次写入生效, 后续重复调用
 * 不再追加, 同批重复 Action 或恢复重放都不会产生两条矛盾 PENDING; 既有记录为 PENDING 时 [pending] 仍能
 * 查到它 ("上次未完成"), 已是 DONE/FAILED 则重放方据此跳过。读侧另有折叠兜底。
 *
 * **安全**: 绝不落参数原文 (只落 SHA-256 摘要); 日志只输出会话 id/文件名/行数, 不含命令文本。
 *
 * @param dir 落盘目录, 默认 `{BASE}/会话检查点/intents` (与检查点同目录树、同生命周期清理)。
 */
class ToolIntentLog(private val dir: String = "${DataPaths.CHECKPOINTS}/intents") {

    private val store = ToolIntentStore(dir)

    /** sessionId → 已落盘过的 toolCallId 集合 (懒加载自磁盘), 用于 [begin] 的幂等判定。 */
    private val known = HashMap<String, MutableSet<String>>()

    /** sessionId → toolCallId → 最近一次 begin 的完整意图 ([finish] 需要基线字段来补全记录)。 */
    private val started = HashMap<String, MutableMap<String, ToolIntent>>()

    /** 上次周期压实的时刻 (哨兵 [NEVER_PRUNED] = 本实例尚未压实过)。仅在 [ToolIntentStore.ioLock] 内读写。 */
    private var lastPrunedAtMs: Long = NEVER_PRUNED

    /** 声明"即将执行"的意图 (WAL 写前记录)。返回时记录已落盘并 fsync — 调用方随后才可执行副作用。 */
    suspend fun begin(intent: ToolIntent) {
        withContext(Dispatchers.IO) {
            synchronized(store.ioLock) {
                // 周期压实: 工具执行路径不该永久增行 — 日志只对"最近一次崩溃"有用 (理由见 pruneIfDue)
                pruneIfDue()
                val seen = knownFor(intent.sessionId)
                // 幂等: 同一 toolCallId 只写一次 (既有 PENDING = 上次未完成; 既有 DONE/FAILED = 已有结论)
                if (seen.contains(intent.toolCallId)) return@synchronized
                if (store.append(store.fileFor(intent.sessionId), intent)) {
                    seen.add(intent.toolCallId)
                    startedFor(intent.sessionId)[intent.toolCallId] = intent
                }
            }
        }
    }

    /**
     * 记录执行结论。
     *
     * **只 flush 不 fsync** (与 [begin] 刻意不对称): DONE/FAILED 丢失的方向是**安全**的 — 恢复侧看到
     * 的是 PENDING, 只会保守提示"先核对再决定是否重试", 不会盲目重放; 而为它付 fsync 是纯开销, 且
     * [begin] 的 fsync 仍严格早于工具执行, WAL 顺序不因此改变。
     *
     * @param outcomeDigest 结果摘要 ([digestOf]); 失败/超时传 null。
     * @param error 失败原因 (非空即 [IntentState.FAILED]); 落盘前脱敏截断。
     */
    suspend fun finish(sessionId: String, toolCallId: String, outcomeDigest: String?, error: String?) {
        withContext(Dispatchers.IO) {
            synchronized(store.ioLock) {
                // 基线优先取本进程 begin 的意图; begin 因幂等被跳过 (恢复重放场景) 时回落到磁盘
                val now = System.currentTimeMillis()
                // 兜底 (理论不可达): 基线缺失 → step=-1 标记, 字段序见 ToolIntent 声明
                val base = startedFor(sessionId)[toolCallId]
                    ?: readLatestLocked(sessionId, toolCallId)
                    ?: ToolIntent(sessionId, -1, toolCallId, "", "", IntentState.PENDING, now)
                val completed = base.copy(
                    state = if (error == null) IntentState.DONE else IntentState.FAILED,
                    finishedAt = now,
                    outcomeDigest = outcomeDigest,
                    error = error?.let { sanitizeError(it) }
                )
                if (store.append(store.fileFor(sessionId), completed, durable = false)) {
                    knownFor(sessionId).add(toolCallId)
                    startedFor(sessionId).remove(toolCallId)
                }
            }
        }
    }

    /**
     * 未完成 (PENDING) 的调用 — 恢复方据此判断"这一步可能已产生副作用"。
     * 同步磁盘读取 (恢复路径在非挂起上下文使用); [ToolIntentStore.read] 已按 toolCallId 折叠去重。
     */
    fun pending(sessionId: String): List<ToolIntent> = synchronized(store.ioLock) {
        (store.read(store.fileFor(sessionId)) ?: emptyList())
            .filter { it.state == IntentState.PENDING }
    }

    /** 清空某会话的全部意图记录 (会话结束/成功收尾后调用)。 */
    suspend fun clear(sessionId: String) {
        withContext(Dispatchers.IO) {
            synchronized(store.ioLock) {
                store.delete(store.fileFor(sessionId))
                known.remove(sessionId)
                started.remove(sessionId)
            }
        }
    }

    /**
     * 清理过期记录, **保留活跃 PENDING** — 过期锚点 = [ToolIntent.finishedAt] ?: [ToolIntent.startedAt];
     * PENDING 无论多旧一律保留 (它们是恢复判重的唯一依据); 清理同时**压实** (每个 toolCallId 只留一行)。
     *
     * @param maxAgeMs <= 0 不做清理并返回 0 (防误传); @return 被删的 intent 条数 (按 toolCallId 计)
     */
    fun pruneOlderThan(nowMs: Long, maxAgeMs: Long): Int = synchronized(store.ioLock) {
        if (maxAgeMs <= 0L) return@synchronized 0
        val cutoff = nowMs - maxAgeMs
        var pruned = 0
        for (f in store.listFiles()) {
            val latest = (store.read(f) ?: emptyList()).associateBy { it.toolCallId }
            val keep = latest.filterValues {
                it.state == IntentState.PENDING || (it.finishedAt ?: it.startedAt) > cutoff
            }
            pruned += latest.size - keep.size
            if (keep.isEmpty()) store.delete(f) else store.rewrite(f, keep.values.toList())
            // 索引同步: 被清掉的 id 必须从 known 移除 (否则后续 begin 会被误判为"已写过"), 基线同步清掉;
            // 所有记录同属一个会话, 故取任一记录即可定位索引键
            val sid = latest.values.firstOrNull()?.sessionId
            if (sid != null) {
                known[sid] = keep.keys.toMutableSet()
                started[sid] = HashMap()
            }
        }
        pruned
    }

    /**
     * 到期才执行的周期压实 — 由 [begin] 在同一把 [ToolIntentStore.ioLock] 内调用, 调用点不需要任何定时逻辑。
     * 周期 [PRUNE_INTERVAL_MS] = 1 小时: 本日志只对"最近一次崩溃"有用, 越旧越无价值, 而压实要整目录
     * 重写, 太勤是白烧工具执行路径的 IO。失败静默: 清理异常**绝不**影响工具执行 (WAL 是旁路设施)。
     *
     * @param nowMs 显式时钟 (可注入, 供测试断言"到期才压实"); @return true = 本次真的执行了一次压实
     */
    internal fun pruneIfDue(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(store.ioLock) {
        if (lastPrunedAtMs != NEVER_PRUNED && nowMs - lastPrunedAtMs < PRUNE_INTERVAL_MS) return@synchronized false
        // 先记账再压: 压实抛异常时也不至于每次 begin 都重试 (失败静默, 下个周期再说)
        lastPrunedAtMs = nowMs
        try {
            pruneOlderThan(nowMs, MAX_AGE_MS)
            true
        } catch (e: Exception) {
            ErrorCollector.report(e, "ToolIntentLog.pruneIfDue")
            false
        }
    }

    // ── 内存索引 (全部在 store.ioLock 内调用) ──────────────────────────

    private fun knownFor(sessionId: String): MutableSet<String> {
        known[sessionId]?.let { return it }
        // 懒加载: 首次触达该会话时把磁盘上已有的 toolCallId 装进内存。
        // 读失败时也缓存空集合 (后果仅可能是多写一条重复行, 读侧折叠后语义不变)。
        val loaded = store.read(store.fileFor(sessionId)) ?: emptyList()
        val set = loaded.mapTo(HashSet()) { it.toolCallId }
        known[sessionId] = set
        return set
    }

    private fun startedFor(sessionId: String): MutableMap<String, ToolIntent> =
        started.getOrPut(sessionId) { HashMap() }

    private fun readLatestLocked(sessionId: String, toolCallId: String): ToolIntent? =
        (store.read(store.fileFor(sessionId)) ?: emptyList()).firstOrNull { it.toolCallId == toolCallId }

    /** 错误文本脱敏 + 截断 (凭据安全: 工具错误可能回显了带密钥的参数或 URL)。 */
    private fun sanitizeError(error: String): String =
        runCatching { Sanitizer.sanitize(error) }.getOrElse { error }.take(MAX_ERROR_CHARS)

    companion object {
        /** 文件后缀 — 每会话一个 JSONL。 */
        const val SUFFIX = ".jsonl"

        /** 错误文本落盘上限 (只作审计线索, 不需全文)。 */
        private const val MAX_ERROR_CHARS = 500

        /** [pruneIfDue] 的压实周期 (1 小时) — 取值理由见该方法 KDoc。 */
        const val PRUNE_INTERVAL_MS = 60 * 60 * 1000L

        /** 记录保留期 (24 小时) — 崩溃恢复只需最近一次, 更旧的 DONE/FAILED 无诊断价值。 */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /** 上次压实时刻的哨兵 — 让新实例的首次 [pruneIfDue] 一定压实 (清掉历史遗留的巨型日志)。 */
        private const val NEVER_PRUNED = Long.MIN_VALUE

        /** 文件名安全化: 只保留 [A-Za-z0-9_-], 防路径穿越与非法字符 (sessionId 通常为 8 位 UUID)。 */
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9_-]")

        /** 会话对应的日志文件名 (同一会话恒定, 供恢复方/测试直接定位)。 */
        fun fileNameFor(sessionId: String): String =
            sessionId.replace(UNSAFE_CHARS, "_") + SUFFIX

        /** 稳定可复现的调用标识 — 重放同一 (会话, 步, 工具, 参数) 必得同一 id (幂等判重的锚点)。 */
        fun toolCallIdOf(sessionId: String, step: Int, toolName: String, argsDigest: String): String =
            "$sessionId-$step-$toolName-${argsDigest.take(16)}"

        /** 文本摘要 — SHA-256 hex 前 16 位; 只落摘要不落原文 (参数可能含 API Key 等凭据)。 */
        fun digestOf(text: String): String = runCatching {
            toHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))).take(16)
        }.getOrElse {
            // 理论不可达 (SHA-256 是全部 JVM/Android 标准算法); 退化为稳定哈希, 保证可复现
            val h = text.hashCode().toLong() and 0xFFFFFFFFL
            h.toString(16).padStart(8, '0').repeat(2)
        }

        /**
         * 错误文案落盘前的第一道防线: 按**值**剔除命令行与参数原文 (框架错误常逐字内嵌命令行);
         * 第二道防线是 [finish] 内的 [Sanitizer.sanitize] 模式脱敏 (兜住工具自行回显的凭据形态)。
         */
        fun scrubError(error: String, commandLine: String): String {
            var out = error
            if (commandLine.isNotEmpty()) out = out.replace(commandLine, "<命令>")
            val args = commandLine.substringAfter(' ', "")
            if (args.isNotEmpty()) out = out.replace(args, "<参数>")
            return out
        }
    }
}

private fun toHex(bytes: ByteArray): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
    }
    return sb.toString()
}
