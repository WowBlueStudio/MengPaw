// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.security.Sanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
 * @property step ReAct 步序号 (0 基, 与 `Checkpoint.step` 同坐标系); finish 找不到 begin 基线
 *   时的兜底记录为 `-1` (标记"基线缺失", 不影响 toolCallId 判重)。
 * @property toolCallId 稳定可复现的调用标识 — 见 [ToolIntentLog.toolCallIdOf]。
 * @property toolName 工具/命令名 (命令行首个 token)。
 * @property argsDigest **参数文本的 SHA-256 hex 前 16 位** — 严禁落参数原文 (参数可能含 API
 *   Key 等凭据, 凭据是项目唯一安全禁区)。
 * @property outcomeDigest 结果文本摘要 (同 [argsDigest] 算法); 成功且结果空白时为 null。
 * @property error 失败原因; 落盘前先经 [ToolIntentLog.scrubError] 剔除参数原文, 再经
 *   [Sanitizer.sanitize] 脱敏并截断 (工具错误可能回显带密钥的参数或 URL)。
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
 * 工具副作用意图日志 (Write-Ahead Intent Log)。
 *
 * **为什么需要**: 检查点每 5 步写一次且写在工具执行**之后** — 崩溃若落在"工具已执行、完成记录
 * 未落盘"之间, 恢复重放会**重复产生副作用** (重复写文件/发消息/下单)。本日志把"将要执行"的
 * 意图在**执行之前**落盘, 使恢复方能在重放前识别出"上次未完成"的调用。
 *
 * **落盘格式**: 每会话一个 JSONL (`{dir}/{安全化 sessionId}.jsonl`), 追加写单行 JSON; `finish`
 * 不改写历史行, 而是再追加一条同 toolCallId 的终态记录, 读侧按 toolCallId 折叠 (后写覆盖先写)。
 * 选它而非"每 intent 一个小文件": ① 追加 O(1) 且不破坏既有记录, 而后者的 finish 需读-改-写整
 * 文件; ② 行序即时序, 崩溃分析无需额外归并; ③ 由 [pruneOlderThan] 压实。
 *
 * **原子性与"新记录先可见、不产生半行"**: ① [begin] 在工具执行前写入并 `fsync` (WAL 顺序:
 * 记录先持久可见, 副作用后发生); ② 追加前若文件末尾不是换行 (上次崩溃留下的半行) 先补一个
 * 换行, 保证新记录独占一行 — 半行只会被读侧跳过, 绝不与下条记录粘连; ③ 记录内不可能出现裸
 * 换行 (JSON 转义保证, 另有防御性替换); ④ [pruneOlderThan] 整文件重写走 tmp + `Files.move` 原子替换。
 *
 * **容错**: 全部文件 IO 一律 try/catch — 读侧遇损坏行/损坏文件跳过不抛, 写侧失败只记错误、
 * 不中断工具执行 (本日志是旁路观测设施, 其故障不得改变 Agent 行为)。
 *
 * **并发**: 单锁 (synchronized) 保护全部读写与内存索引。`executeActions` 并行执行一批工具, 故
 * [begin]/[finish] 会被并发调用 — 保证线程安全, 且**不串行化工具执行本身** (锁只覆盖"一次小
 * 追加 + fsync", 工具执行仍在各自协程并行)。
 *
 * **同一 toolCallId 重复 begin 的语义 (定案: 保留既有状态)**: [begin] 对同一 toolCallId
 * **幂等** — 首次写入生效, 后续重复调用不再追加记录, 因此同批重复 Action 或恢复重放都不会产生
 * 两条矛盾的 PENDING; 既有记录仍为 [IntentState.PENDING] 时 [pending] 仍可查到它 (即"**上次未
 * 完成**"语义), 已是 DONE/FAILED 则说明已有结论, 重放方据此跳过重复副作用。读侧另有折叠兜底。
 *
 * **安全**: 绝不落参数原文 (只落 SHA-256 摘要); 日志只输出会话 id/文件名/行数, 不含命令文本。
 *
 * @param dir 落盘目录, 默认 `{BASE}/会话检查点/intents` (与检查点同目录树、同生命周期清理)。
 */
class ToolIntentLog(private val dir: String = "${DataPaths.CHECKPOINTS}/intents") {

    private val json = Json { ignoreUnknownKeys = true }

    /** 全局锁 — 保护文件读写与两个内存索引 (并行工具批会并发调用 [begin]/[finish])。 */
    private val ioLock = Any()

    /** sessionId → 已落盘过的 toolCallId 集合 (懒加载自磁盘), 用于 [begin] 的幂等判定。 */
    private val known = HashMap<String, MutableSet<String>>()

    /** sessionId → toolCallId → 最近一次 begin 的完整意图 ([finish] 需要基线字段来补全记录)。 */
    private val started = HashMap<String, MutableMap<String, ToolIntent>>()

    /** 声明"即将执行"的意图 (WAL 写前记录)。返回时记录已落盘并 fsync — 调用方随后才可执行副作用。 */
    suspend fun begin(intent: ToolIntent) {
        withContext(Dispatchers.IO) {
            synchronized(ioLock) {
                val seen = knownFor(intent.sessionId)
                // 幂等: 同一 toolCallId 只写一次 (既有 PENDING = 上次未完成; 既有 DONE/FAILED = 已有结论)
                if (seen.contains(intent.toolCallId)) return@synchronized
                if (appendLocked(intent.sessionId, intent)) {
                    seen.add(intent.toolCallId)
                    startedFor(intent.sessionId)[intent.toolCallId] = intent
                }
            }
        }
    }

    /**
     * 记录执行结论。
     * @param outcomeDigest 结果摘要 ([digestOf]); 失败/超时传 null。
     * @param error 失败原因 (非空即 [IntentState.FAILED]); 落盘前脱敏截断。
     */
    suspend fun finish(sessionId: String, toolCallId: String, outcomeDigest: String?, error: String?) {
        withContext(Dispatchers.IO) {
            synchronized(ioLock) {
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
                if (appendLocked(sessionId, completed)) {
                    knownFor(sessionId).add(toolCallId)
                    startedFor(sessionId).remove(toolCallId)
                }
            }
        }
    }

    /**
     * 未完成 (PENDING) 的调用 — 恢复方据此判断"这一步可能已产生副作用"。
     * 同步磁盘读取 (恢复路径在非挂起上下文使用), 按 toolCallId 折叠去重, 保持行序。
     */
    fun pending(sessionId: String): List<ToolIntent> = synchronized(ioLock) {
        val all = readParsedFile(fileFor(sessionId)) ?: emptyList()
        foldLatest(all).values.filter { it.state == IntentState.PENDING }
    }

    /** 清空某会话的全部意图记录 (会话结束/成功收尾后调用)。 */
    suspend fun clear(sessionId: String) {
        withContext(Dispatchers.IO) {
            synchronized(ioLock) {
                val f = fileFor(sessionId)
                try {
                    if (f.exists()) f.delete()
                    val tmp = File(f.parentFile, "${f.name}.tmp")
                    if (tmp.exists()) tmp.delete()
                } catch (e: Exception) {
                    ErrorCollector.report(e, "ToolIntentLog.clear")
                }
                known.remove(sessionId)
                started.remove(sessionId)
            }
        }
    }

    /**
     * 清理过期记录, **保留活跃 PENDING**。
     * 过期判定锚点 = [ToolIntent.finishedAt] ?: [ToolIntent.startedAt];
     * PENDING 记录无论是何时开始的一律保留 (它们是恢复判重的唯一依据)。
     * 清理同时**压实**日志 (每个 toolCallId 只留最终状态一行)。
     *
     * @param maxAgeMs <= 0 时不做任何清理 (防误传), 返回 0。
     * @return 被删除的 intent **条数** (按 toolCallId 计, 非行数)。
     */
    fun pruneOlderThan(nowMs: Long, maxAgeMs: Long): Int = synchronized(ioLock) {
        if (maxAgeMs <= 0L) return@synchronized 0
        val cutoff = nowMs - maxAgeMs
        val files = try {
            File(dir).listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
        } catch (e: Exception) {
            ErrorCollector.report(e, "ToolIntentLog.prune")
            null
        } ?: return@synchronized 0
        var pruned = 0
        for (f in files) {
            val latest = foldLatest(readParsedFile(f) ?: emptyList())
            val keep = latest.filterValues {
                it.state == IntentState.PENDING || (it.finishedAt ?: it.startedAt) > cutoff
            }
            pruned += latest.size - keep.size
            try {
                if (keep.isEmpty()) {
                    if (f.exists()) f.delete()
                } else {
                    writeAllLocked(f, keep.values.toList())
                }
            } catch (e: Exception) {
                ErrorCollector.report(e, "ToolIntentLog.prune.write")
            }
            // 索引同步: 被清掉的 id 必须从 known 移除, 否则后续 begin 会被误判为"已写过"
            val sid = latest.values.firstOrNull()?.sessionId
            if (sid != null) {
                known[sid] = keep.keys.toMutableSet()
                started[sid]?.keys?.removeAll(latest.keys - keep.keys)
            }
        }
        pruned
    }

    // ── 内部实现 (全部在 ioLock 内调用) ────────────────────────────────

    private fun fileFor(sessionId: String): File = File(dir, fileNameFor(sessionId))

    private fun knownFor(sessionId: String): MutableSet<String> {
        known[sessionId]?.let { return it }
        // 懒加载: 首次触达该会话时把磁盘上已有的 toolCallId 装进内存。
        // 读失败时也缓存空集合 (后果仅可能是多写一条重复行, 读侧折叠后语义不变)。
        val loaded = readParsedFile(fileFor(sessionId)) ?: emptyList()
        val set = foldLatest(loaded).keys.toMutableSet()
        known[sessionId] = set
        return set
    }

    private fun startedFor(sessionId: String): MutableMap<String, ToolIntent> =
        started.getOrPut(sessionId) { HashMap() }

    /** 追加一行 (含防半行的补换行 + fsync)。失败返回 false, 绝不抛。 */
    private fun appendLocked(sessionId: String, intent: ToolIntent): Boolean {
        val f = fileFor(sessionId)
        return try {
            f.parentFile?.mkdirs()
            val encoded = json.encodeToString(intent).replace('\n', ' ')
            FileOutputStream(f, true).use { out ->
                if (needsHealingNewline(f)) out.write(NEWLINE)
                out.write(encoded.toByteArray(Charsets.UTF_8))
                out.write(NEWLINE)
                out.flush()
                try {
                    out.fd.sync()
                } catch (e: Exception) {
                    // 已 flush 到操作系统, 仅物理落盘未确认 — 记录失败但不影响工具执行
                    ErrorCollector.report(e, "ToolIntentLog.fsync")
                }
            }
            true
        } catch (e: Exception) {
            ErrorCollector.report(e, "ToolIntentLog.append")
            false
        }
    }

    /** 文件末尾不是换行 (上次崩溃留半行) → 需要先补一个换行, 让新记录独占一行。 */
    private fun needsHealingNewline(f: File): Boolean = try {
        val len = f.length()
        if (len == 0L) false
        else RandomAccessFile(f, "r").use { raf ->
            raf.seek(len - 1)
            raf.read() != NEWLINE
        }
    } catch (_: Exception) {
        // 读不出结尾 → 保守补一个换行 (多余空行会被读侧跳过, 代价远小于两行粘连)
        true
    }

    /** 原子整文件重写 (tmp + REPLACE_EXISTING), 供 prune 压实使用。 */
    private fun writeAllLocked(f: File, records: List<ToolIntent>) {
        val tmp = File(f.parentFile, "${f.name}.tmp")
        try {
            val body = records.joinToString("") { json.encodeToString(it).replace('\n', ' ') + "\n" }
            FileOutputStream(tmp).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (tmp.exists()) {
                try { tmp.delete() } catch (_: Exception) { /* 残留 tmp 不影响读取 */ }
            }
        }
    }

    /** 容错读取: 整文件读失败返回 null (未知), 单行解析失败跳过并计数。绝不抛。 */
    private fun readParsedFile(f: File): List<ToolIntent>? {
        val lines = try {
            if (!f.isFile) return emptyList()
            f.readLines(Charsets.UTF_8)
        } catch (e: Exception) {
            ErrorCollector.report(e, "ToolIntentLog.read")
            return null
        }
        val out = ArrayList<ToolIntent>(lines.size)
        var skipped = 0
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            try {
                out.add(json.decodeFromString<ToolIntent>(t))
            } catch (_: Exception) {
                skipped++
            }
        }
        if (skipped > 0) {
            KernelLog.w(TAG, "跳过 $skipped 条损坏记录 (文件 ${f.name})")
        }
        return out
    }

    private fun readLatestLocked(sessionId: String, toolCallId: String): ToolIntent? =
        foldLatest(readParsedFile(fileFor(sessionId)) ?: emptyList())[toolCallId]

    /** 按 toolCallId 折叠 (后写覆盖先写), 保留首次出现位置 — 重复/中间态在此收敛为最终态。 */
    private fun foldLatest(all: List<ToolIntent>): LinkedHashMap<String, ToolIntent> {
        val m = LinkedHashMap<String, ToolIntent>()
        for (item in all) m[item.toolCallId] = item
        return m
    }

    /** 错误文本脱敏 + 截断 (凭据安全: 工具错误可能回显了带密钥的参数或 URL)。 */
    private fun sanitizeError(error: String): String = try {
        Sanitizer.sanitize(error).take(MAX_ERROR_CHARS)
    } catch (_: Exception) {
        error.take(MAX_ERROR_CHARS)
    }

    companion object {
        private const val TAG = "ToolIntentLog"

        /** 文件后缀 — 每会话一个 JSONL。 */
        const val SUFFIX = ".jsonl"

        /** 错误文本落盘上限 (只作审计线索, 不需全文)。 */
        private const val MAX_ERROR_CHARS = 500

        /** 文件名安全化: 只保留 [A-Za-z0-9_-], 防路径穿越与非法字符 (sessionId 通常为 8 位 UUID)。 */
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9_-]")

        /** 会话对应的日志文件名 (同一会话恒定, 供恢复方/测试直接定位)。 */
        fun fileNameFor(sessionId: String): String =
            sessionId.replace(UNSAFE_CHARS, "_") + SUFFIX

        /**
         * 稳定可复现的调用标识: `"${sessionId}-${step}-${toolName}-${argsDigest.take(16)}"`。
         * 重放同一 (会话, 步, 工具, 参数) 必得同一 id — 这是幂等判重的锚点。
         */
        fun toolCallIdOf(sessionId: String, step: Int, toolName: String, argsDigest: String): String =
            "$sessionId-$step-$toolName-${argsDigest.take(16)}"

        /**
         * 文本摘要 — SHA-256 hex 前 16 位。
         * 只落摘要不落原文: 参数可能含 API Key 等凭据 (凭据是唯一安全禁区)。
         */
        fun digestOf(text: String): String = try {
            val md = MessageDigest.getInstance("SHA-256")
            toHex(md.digest(text.toByteArray(Charsets.UTF_8))).take(16)
        } catch (_: Exception) {
            // 理论不可达 (SHA-256 是全部 JVM/Android 的标准算法); 退化为稳定哈希, 保证可复现
            val h = text.hashCode().toLong() and 0xFFFFFFFFL
            h.toString(16).padStart(8, '0').repeat(2)
        }

        /**
         * 错误文案落盘前的第一道防线: 按**值**剔除本次调用的命令行与参数原文 — 框架错误常逐字内嵌
         * 命令行 (如"命令超时 (60s): fs.write path=/x key=sk-…"), 原样入库等于把参数原文落盘。
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

/** 换行符字节值 (`'\n'.code`) — JSONL 的行分隔符。 */
private const val NEWLINE = 10

private fun toHex(bytes: ByteArray): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
    }
    return sb.toString()
}
