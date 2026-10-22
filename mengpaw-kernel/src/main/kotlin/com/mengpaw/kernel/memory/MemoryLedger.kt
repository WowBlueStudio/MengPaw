// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆账本 (P3, docs/memory-git-sync-design.md §6.1) — `{AGENTS}/twin/ledger/<agent>/entries.jsonl`。
 *
 * ## 语义
 * - **只增**: 每行一条 JSON, 永不改写/删除历史行; 纠错靠追加 `kind=revision` + `supersedes`。
 * - **正文留在文件里**: 条目只存 `blockHash`; 块正文仍由 `memory/` 下的 md 文件承载。
 * - **并集合并**: 两设备账本按 `entryId` 去重后并集, 顺序按 `(wall, entryId)` 排序 —
 *   与合并方向无关, 双方算出同一文本才能自然收敛 (`MergeText.unionJsonl` 同规则)。
 * - **损坏防御**: 坏行跳过并记 [ErrorCollector], 不中断读取。
 * - **三重上限**: 条数 / 单行长度 / 文件体积, 超限返回错误 (不静默截断)。
 */
object MemoryLedger {

    const val TRACK_MID = "mid"
    const val TRACK_PROJECT = "project"

    const val KIND_NOTE = "note"
    const val KIND_REVISION = "revision"
    const val KIND_DIGEST = "digest"

    /** 哈希统一前缀 (与设计示例 `sha256:…` 一致)。 */
    const val HASH_PREFIX = "sha256:"

    /** 单账本条目上限。 */
    const val MAX_ENTRIES = 20_000
    /** 单行 (单条) 上限 — 8KB, 防 Agent 把整段对话塞进条目。 */
    const val MAX_LINE_BYTES = 8 * 1024
    /** 单文件上限 — 8MB。 */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ID_FIELD = Regex("\"entryId\"\\s*:\\s*\"([^\"]+)\"")
    private val WALL_FMT = "yyyy-MM-dd'T'HH:mm:ssXXX"

    /** 条目信封 (§6.1; 任务清单未列的可选 `message` 字段不落地)。 */
    @Serializable
    data class Entry(
        val entryId: String = "",
        val track: String = TRACK_MID,
        val file: String = "",
        val blockHash: String = "",
        val project: String = "",
        val agent: String = "",
        val device: String = "",
        val lamport: Long = 0L,
        val wall: String = "",
        val session: String = "",
        val supersedes: List<String> = emptyList(),
        val kind: String = KIND_NOTE,
        val sources: List<String> = emptyList()
    )

    /** 追加结果: `appended=false && error==null` = 重复条目 (幂等跳过)。 */
    data class AppendResult(val appended: Boolean, val entryId: String = "", val error: String? = null)

    /** 读取结果: 坏行数量供报告/测试。 */
    data class ReadResult(val entries: List<Entry>, val badLines: Int)

    private class ScanCache(val path: String, val length: Long, val count: Int, val ids: Set<String>)
    private var cache: ScanCache? = null

    // ── 路径 ───────────────────────────────────────────────────────

    fun file(agentName: String): File = File(DataPaths.ledgerFile(agentName))

    /** 绝对路径 → 工作区相对路径 (`memory/memory_2026-10-22.md`)。 */
    fun relativePath(agentName: String, absolutePath: String): String {
        val root = File(DataPaths.resolver.agentDir(agentName)).absolutePath.replace('\\', '/').trimEnd('/')
        val abs = File(absolutePath).absolutePath.replace('\\', '/')
        return if (abs.startsWith("$root/")) abs.removePrefix("$root/") else File(absolutePath).name
    }

    // ── 条目构造 ───────────────────────────────────────────────────

    /** 当前本地墙钟 (带时区偏移, 便于按本地日期分组)。 */
    fun nowWall(): String = SimpleDateFormat(WALL_FMT, Locale.US).format(Date())

    /** 下一个 Lamport 时钟 (本账本内单调; 并集后取 max, 不需全局一致)。 */
    fun nextLamport(agentName: String): Long =
        (entries(agentName).maxOfOrNull { it.lamport } ?: 0L) + 1L

    /** 构造并补齐 `entryId/wall/lamport` 的条目 (不落盘)。 */
    fun newEntry(
        agentName: String,
        track: String,
        file: String,
        block: String,
        project: String = "",
        device: String = "",
        session: String = "",
        kind: String = KIND_NOTE,
        supersedes: List<String> = emptyList(),
        sources: List<String> = emptyList(),
        wall: String = nowWall(),
        lamport: Long = 0L
    ): Entry {
        val stamped = Entry(
            entryId = "",
            track = track,
            file = file,
            blockHash = blockHash(block),
            project = project,
            agent = agentName,
            device = device,
            lamport = if (lamport > 0) lamport else nextLamport(agentName),
            wall = wall,
            session = session,
            supersedes = supersedes,
            kind = kind,
            sources = sources
        )
        return stamped.copy(entryId = computeEntryId(stamped))
    }

    /** 记录一条普通中期/项目条目。 */
    fun appendNote(
        agentName: String,
        track: String,
        file: String,
        block: String,
        project: String = "",
        device: String = "",
        session: String = "",
        kind: String = KIND_NOTE,
        supersedes: List<String> = emptyList(),
        sources: List<String> = emptyList(),
        wall: String = nowWall(),
        lamport: Long = 0L
    ): AppendResult = append(
        newEntry(agentName, track, file, block, project, device, session, kind, supersedes, sources, wall, lamport)
    )

    /** 记录归纳视图条目 (digest) — `sources` 即已归纳的 entryId 集合。 */
    fun appendDigest(
        agentName: String,
        viewFile: String,
        sources: List<String>,
        viewText: String,
        device: String = "",
        session: String = ""
    ): AppendResult = appendNote(
        agentName = agentName,
        track = TRACK_MID,
        file = viewFile,
        block = viewText,
        device = device,
        session = session,
        kind = KIND_DIGEST,
        sources = sources.distinct()
    )

    // ── 追加 ───────────────────────────────────────────────────────

    /** 只追加一行; 上限/重复/IO 错误全部返回结果, 不抛。 */
    @Synchronized
    fun append(entry: Entry): AppendResult {
        val stamped = stamp(entry)
        val line = encode(stamped)
        val lineBytes = line.toByteArray(Charsets.UTF_8).size
        if (lineBytes > MAX_LINE_BYTES) {
            return AppendResult(false, stamped.entryId, "账本单条超限 ($lineBytes > $MAX_LINE_BYTES 字节)")
        }
        val target = file(stamped.agent)
        val currentLength = try { if (target.isFile) target.length() else 0L } catch (_: Exception) { 0L }
        if (currentLength + lineBytes + 1 > MAX_FILE_BYTES) {
            return AppendResult(false, stamped.entryId, "账本文件超限 (追加后 ${currentLength + lineBytes + 1} > $MAX_FILE_BYTES 字节)")
        }
        val scan = scan(target)
        if (scan.count >= MAX_ENTRIES) {
            return AppendResult(false, stamped.entryId, "账本条数超限 (${scan.count} >= $MAX_ENTRIES)")
        }
        if (scan.ids.contains(stamped.entryId)) return AppendResult(false, stamped.entryId, null)
        return try {
            target.parentFile?.mkdirs()
            target.appendText(line + "\n", Charsets.UTF_8)
            cache = ScanCache(target.path, currentLength + lineBytes + 1, scan.count + 1, scan.ids + stamped.entryId)
            AppendResult(true, stamped.entryId, null)
        } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryLedger.append")
            AppendResult(false, stamped.entryId, "账本写入失败: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * 同步层并集后整文件替换 — 与 [append] 同一把锁, 避免"追加到一半被覆盖"。
     * 只允许同步层调用; 原子写 (tmp + move), 失败不动原文件。
     */
    @Synchronized
    fun replaceFromSync(agentName: String, bytes: ByteArray): Boolean {
        val target = file(agentName)
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "entries.jsonl.sync.tmp")
            tmp.writeBytes(bytes)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            cache = null
            true
        } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryLedger.replaceFromSync")
            false
        }
    }

    private fun stamp(entry: Entry): Entry {        if (entry.entryId.isNotBlank() && entry.wall.isNotBlank() && entry.lamport > 0) return entry
        val wall = entry.wall.ifBlank { nowWall() }
        val lamport = if (entry.lamport > 0) entry.lamport else nextLamport(entry.agent)
        val base = entry.copy(entryId = "", wall = wall, lamport = lamport)
        return base.copy(entryId = computeEntryId(base))
    }

    private fun encode(entry: Entry): String = json.encodeToString(Entry.serializer(), entry)

    private fun computeEntryId(entry: Entry): String {
        val canonical = listOf(
            entry.track, entry.file, entry.blockHash, entry.project, entry.agent, entry.device,
            entry.lamport.toString(), entry.wall, entry.session, entry.kind,
            entry.supersedes.joinToString(","), entry.sources.joinToString(",")
        ).joinToString("\u0001")
        return HASH_PREFIX + sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    // ── 读取 ───────────────────────────────────────────────────────

    /** 读全部条目 (文件顺序); 坏行跳过并计数。 */
    fun read(agentName: String): ReadResult {
        val target = file(agentName)
        if (!target.isFile) return ReadResult(emptyList(), 0)
        val out = mutableListOf<Entry>()
        var bad = 0
        try {
            target.forEachLine { raw ->
                val line = raw.trim()
                if (line.isNotEmpty()) {
                    val entry = try {
                        json.decodeFromString(Entry.serializer(), line)
                    } catch (e: Exception) {
                        if (bad < MAX_BAD_LINE_REPORTS) ErrorCollector.report(e, "MemoryLedger.parse")
                        bad++
                        null
                    }
                    if (entry != null) {
                        if (entry.entryId.isBlank()) {
                            if (bad < MAX_BAD_LINE_REPORTS) {
                                ErrorCollector.report(IOException("账本条目缺 entryId"), "MemoryLedger.parse")
                            }
                            bad++
                        } else {
                            out.add(entry)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryLedger.read")
        }
        return ReadResult(out, bad)
    }

    fun entries(agentName: String): List<Entry> = read(agentName).entries

    /** 坏行上报上限 — 坏文件不刷爆 ErrorCollector。 */
    private const val MAX_BAD_LINE_REPORTS = 5

    private fun scan(target: File): ScanCache {
        val length = try { if (target.isFile) target.length() else 0L } catch (_: Exception) { 0L }
        val cached = cache
        if (cached != null && cached.path == target.path && cached.length == length) return cached
        val ids = LinkedHashSet<String>()
        var count = 0
        if (target.isFile) {
            try {
                target.forEachLine { line ->
                    if (line.isNotBlank()) {
                        count++
                        ID_FIELD.find(line)?.groupValues?.get(1)?.let { ids.add(it) }
                    }
                }
            } catch (e: Exception) {
                ErrorCollector.report(e, "MemoryLedger.scan")
            }
        }
        return ScanCache(target.path, length, count, ids).also { cache = it }
    }

    // ── 查询 (设计 §6.3: 未归纳 / 日期区间 / 项目 / 文件) ────────────

    fun byFile(agentName: String, file: String): List<Entry> = entries(agentName).filter { it.file == file }

    fun byProject(agentName: String, project: String): List<Entry> =
        entries(agentName).filter { it.project == project }

    /** 已被任一 digest 条目收录的 entryId 集合。 */
    fun digestedIds(agentName: String): Set<String> =
        digestedIds(entries(agentName))

    fun digestedIds(all: List<Entry>): Set<String> =
        all.filter { it.kind == KIND_DIGEST }.flatMap { it.sources }.toSet()

    /** 未归纳 = 非 digest 且未被任何 digest 的 sources 收录。 */
    fun undigested(agentName: String): List<Entry> = undigested(entries(agentName))

    fun undigested(all: List<Entry>): List<Entry> {
        val digested = digestedIds(all)
        return all.filter { it.kind != KIND_DIGEST && it.entryId !in digested }
    }

    /**
     * 组合查询; `fromDate`/`toDate` 为本地日期 `yyyy-MM-dd` (含端点, 比对 `wall` 前缀)。
     * `undigested=true` 时以全账本计算已归纳集合 (避免范围过滤漏掉 digest 条目)。
     */
    fun query(
        agentName: String,
        file: String? = null,
        project: String? = null,
        undigestedOnly: Boolean = false,
        fromDate: String? = null,
        toDate: String? = null
    ): List<Entry> {
        val all = entries(agentName)
        val digested = if (undigestedOnly) digestedIds(all) else emptySet()
        return all.filter { entry ->
            (file == null || entry.file == file) &&
                (project == null || entry.project == project) &&
                (fromDate == null || entry.wall.take(10) >= fromDate) &&
                (toDate == null || entry.wall.take(10) <= toDate) &&
                (!undigestedOnly || (entry.kind != KIND_DIGEST && entry.entryId !in digested))
        }
    }

    // ── 并集 ───────────────────────────────────────────────────────

    /**
     * 两设备账本并集: 按 `entryId` 去重 (同 id 取字典序较小行, 保证交换律),
     * 排序 `(wall, entryId)` — 与 `MergeText.unionJsonl` 同规则, 合并方向无关。
     */
    fun unionMerge(a: List<Entry>, b: List<Entry>): List<Entry> {
        val chosen = LinkedHashMap<String, Entry>()
        (a + b).forEach { entry ->
            val prev = chosen[entry.entryId]
            if (prev == null || encode(entry) < encode(prev)) chosen[entry.entryId] = entry
        }
        return chosen.values.sortedWith(compareBy({ it.wall }, { it.entryId }))
    }

    // ── 块哈希 / 正文解析 ──────────────────────────────────────────

    /** 块正文规范化 — 去 CRLF/首尾空行, 统一 LF (哈希与解析共用)。 */
    fun canonicalBlock(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').trim('\n')

    /** 文件正文 → 块列表 (以 `## ` 开头分段, 规范化)。 */
    fun splitBlocks(text: String): List<String> =
        text.split(Regex("(?=## )")).map(::canonicalBlock).filter { it.startsWith("## ") }

    /** 块标题 — 首行去掉 `## `。 */
    fun titleOf(block: String): String =
        canonicalBlock(block).lineSequence().firstOrNull()?.removePrefix("## ")?.trim().orEmpty()

    /** 块 SHA-256 (`sha256:<hex>`)。 */
    fun blockHash(block: String): String =
        HASH_PREFIX + sha256Hex(canonicalBlock(block).toByteArray(Charsets.UTF_8))

    /** 按 `blockHash` 从工作区文件取回块正文; 找不到 → null。 */
    fun resolveBlock(agentName: String, entry: Entry): String? {
        val target = File(File(DataPaths.resolver.agentDir(agentName)), entry.file.replace('/', File.separatorChar))
        if (!target.isFile) return null
        val text = try { target.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryLedger.resolveBlock")
            return null
        }
        return splitBlocks(text).firstOrNull { blockHash(it) == entry.blockHash }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
