// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

/**
 * 三方合并器 (P2, docs/memory-git-sync-design.md §5): 纯函数 `(base, ours, theirs) → MergeResult`。
 *
 * ## 输入/输出
 * - 输入: 三个 `relPath → 字节` 映射 + 两侧排序键 [MergeSide] (lamport, deviceId);
 * - 输出: 逐文件 [FileMerge] (原路径内容 / 额外落盘 `.kept-` `.conflict.` / 冲突块 / inbox 提示)。
 * - 本对象**不碰磁盘**: 写工作区、写 inbox、提交快照由调用方 (plugin `TwinSyncV3`) 完成。
 *
 * ## 策略表 (§5.2)
 * | 类 | 策略 |
 * |---|---|
 * | L | frontmatter 键级 + 正文 diff3 |
 * | M | 追加并集 (按 (lamport, deviceId)); 非追加形态回退 diff3 |
 * | D | 单写者; 同 deviceId 双写 → 拒绝合并 + 告警 |
 * | RULES | `twin-model-rules.json` 按 rule.id 并集 |
 * | 删除 vs 修改 | 删除生效, 修改方内容落 `{name}.kept-<device>.md` + inbox |
 * | 兜底 | 非 UTF-8 或 >256KB → 取较新一方 + `.conflict` 备份 |
 * | 无共同基准 | 双方各自新增且内容不同 → 本机为主 + 对方内容 `.conflict` 备份 |
 */
object TreeMerge {

    /** 单文件合并硬上限 — 超过即不合并 (§5.2 兜底)。 */
    const val MAX_MERGE_BYTES = 256 * 1024

    const val RULES_FILE = "twin-model-rules.json"

    private val DREAM_FILE = Regex("^\\d{4}-\\d{2}-\\d{2}_dream\\.md$")

    /** L 类根文档 (memory/memory.md 单列, 其余 memory 目录文件按命名归类)。 */
    private val LONG_TERM_DOCS = setOf(
        "soul.md", "profile.md", "agents.md", "boost.md",
        "trigger.md", "heartbeat.md", "trumanshow.md"
    )

    // ── 分类 (§2.1) ────────────────────────────────────────────────

    /** 工作区相对路径 → L/M/D/rules/S。S 类不参与同步 (扫描阶段已排除)。 */
    fun classify(relPath: String): TwinFileClass {
        val path = relPath.replace('\\', '/')
        // S 类 (短期): 明确排除目录, 便于调用方在扫描之外也能判定
        if (path.startsWith("inbox/") || path.startsWith("dialog/") ||
            path.startsWith("tool_results/") || path.startsWith("memory/backup/")
        ) {
            return TwinFileClass.S
        }
        if (path == RULES_FILE) return TwinFileClass.RULES
        if (path.startsWith("devices/")) return TwinFileClass.D
        if (path in LONG_TERM_DOCS) return TwinFileClass.L
        if (path == "memory/memory.md") return TwinFileClass.L
        if (path.startsWith("memory/")) {
            val name = path.removePrefix("memory/")
            if (name.startsWith("memory_") && name.endsWith(".md")) return TwinFileClass.M
            if (name.startsWith("project_") && name.endsWith("_memory.md")) return TwinFileClass.M
            if (name.endsWith("_dream.md")) return TwinFileClass.M
        }
        if (DREAM_FILE.matches(path)) return TwinFileClass.M
        if (path.endsWith(".md")) return TwinFileClass.L
        return TwinFileClass.S
    }

    // ── 合并入口 ───────────────────────────────────────────────────

    /**
     * 三方合并整个文件集。
     *
     * @param classOf 分类器 (dev 命名空间强制 D 类)
     */
    fun merge(
        base: Map<String, ByteArray>,
        ours: Map<String, ByteArray>,
        theirs: Map<String, ByteArray>,
        oursSide: MergeSide,
        theirsSide: MergeSide,
        classOf: (String) -> TwinFileClass = ::classify
    ): MergeOutcome {
        val paths = (base.keys + ours.keys + theirs.keys).sorted()
        val files = ArrayList<FileMerge>(paths.size)
        for (path in paths) {
            val baseBytes = base[path]
            val oursBytes = ours[path]
            val theirsBytes = theirs[path]
            // 三方都没有 → 空操作 (不产生 delete 噪音)
            if (baseBytes == null && oursBytes == null && theirsBytes == null) continue
            files.add(mergeFile(path, baseBytes, oursBytes, theirsBytes, oursSide, theirsSide, classOf(path)))
        }
        return MergeOutcome(files)
    }

    /**
     * 单文件合并 (纯函数)。
     *
     * @param base 上次同步时的内容; null = 无共同基准 (首次同步/双方各自新增)
     * @param ours 本机内容; null = 本机已删除
     * @param theirs 对端内容; null = 对端已删除
     */
    fun mergeFile(
        relPath: String,
        base: ByteArray?,
        ours: ByteArray?,
        theirs: ByteArray?,
        oursSide: MergeSide,
        theirsSide: MergeSide,
        fileClass: TwinFileClass = classify(relPath)
    ): FileMerge {
        // ① 双方一致 (含双方都删)
        if (bytesEqual(ours, theirs)) return FileMerge(relPath, ours)
        // ② 只有一方变更 → 直接取变更方 (含删除); 对端删除时留 inbox 提示 (删除传播可见)
        if (bytesEqual(ours, base)) {
            return if (theirs == null && ours != null) {
                FileMerge(
                    relPath = relPath,
                    content = null,
                    inboxNote = "- $relPath — 对端已删除, 本机已删除并保留 .deleted 备份"
                )
            } else {
                FileMerge(relPath, theirs)
            }
        }
        if (bytesEqual(theirs, base)) return FileMerge(relPath, ours)
        // ③ 双方都改动
        if (ours == null) return deleteWithKept(relPath, theirs, theirsSide, "本机删除、对端修改")
        if (theirs == null) return deleteWithKept(relPath, ours, oursSide, "对端删除、本机修改")
        if (base == null) {
            // 无共同基准: 保留本机内容, 对方内容另存 .conflict + inbox 提示 (不静默覆盖)
            val lineCount = TextDiff.splitLines(String(ours, Charsets.UTF_8)).size
            return FileMerge(
                relPath = relPath,
                content = ours,
                extras = listOf(MergeExtra(conflictPath(relPath, theirsSide.deviceId), theirs)),
                conflict = MergeConflict(relPath, 1, lineCount, "无共同基准 (首次同步)"),
                inboxNote = "- $relPath — 无共同基准, 对端内容已存为 ${conflictPath(relPath, theirsSide.deviceId)}"
            )
        }
        // ④ 大文件 / 非 UTF-8 兜底
        if (!isMergeable(ours) || !isMergeable(theirs) || !isMergeable(base)) {
            return fallback(relPath, ours, theirs, oursSide, theirsSide)
        }
        val baseText = MergeText.decode(base) ?: return fallback(relPath, ours, theirs, oursSide, theirsSide)
        val oursText = MergeText.decode(ours) ?: return fallback(relPath, ours, theirs, oursSide, theirsSide)
        val theirsText = MergeText.decode(theirs) ?: return fallback(relPath, ours, theirs, oursSide, theirsSide)
        val oursWins = oursSide >= theirsSide
        return when (fileClass) {
            TwinFileClass.RULES -> mergeRules(relPath, baseText, oursText, theirsText, oursSide, theirsSide, oursWins)
            TwinFileClass.M -> {
                val union = MergeText.appendUnion(baseText, oursText, theirsText, oursFirst = !oursWins)
                if (union != null) {
                    FileMerge(relPath, union.text.toByteArray(Charsets.UTF_8))
                } else {
                    diff3File(relPath, baseText, oursText, theirsText, oursSide, theirsSide)
                }
            }
            TwinFileClass.D -> {
                if (oursSide.deviceId == theirsSide.deviceId) {
                    // 单写者铁律被破坏: 拒绝合并, 暴露为身份故障, 不静默覆盖
                    FileMerge(relPath, ours, warning = "同一 deviceId (${oursSide.deviceId}) 双写, 拒绝合并 $relPath")
                } else {
                    FileMerge(relPath, if (oursWins) ours else theirs)
                }
            }
            TwinFileClass.L, TwinFileClass.S -> {
                val merged = MergeText.mergeFrontmatterAndBody(
                    baseText, oursText, theirsText,
                    oursLabel = oursSide.display,
                    theirsLabel = theirsSide.display,
                    oursWins = oursWins,
                    oursDevice = oursSide.display,
                    theirsDevice = theirsSide.display
                )
                fileMergeOf(relPath, merged)
            }
        }
    }

    // ── 产物命名 ───────────────────────────────────────────────────

    /** 删除-修改冲突的保留文件: `{name}.kept-<device>.md` (§5.2)。 */
    fun keptPath(relPath: String, device: String): String {
        val dir = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val suffix = safeDevice(device)
        return if (name.endsWith(".md")) "$prefix${name.removeSuffix(".md")}.kept-$suffix.md"
        else "$prefix$name.kept-$suffix"
    }

    /** 兜底备份名 — 必须含 `.conflict.` (工作区扫描已排除该模式)。 */
    fun conflictPath(relPath: String, device: String): String = "$relPath.conflict.${safeDevice(device)}"

    // ── 内部 ───────────────────────────────────────────────────────

    private fun diff3File(
        relPath: String,
        baseText: String,
        oursText: String,
        theirsText: String,
        oursSide: MergeSide,
        theirsSide: MergeSide
    ): FileMerge = fileMergeOf(
        relPath,
        MergeText.diff3(baseText, oursText, theirsText, oursSide.display, theirsSide.display)
    )

    private fun fileMergeOf(relPath: String, merged: TextMerge): FileMerge =
        FileMerge(
            relPath = relPath,
            content = merged.text.toByteArray(Charsets.UTF_8),
            conflict = if (merged.hasConflict) {
                MergeConflict(relPath, merged.hunks, merged.conflictLines, "行级冲突")
            } else {
                null
            },
            inboxNote = if (merged.hasConflict) {
                "- $relPath — ${merged.hunks} 处冲突 (约 ${merged.conflictLines} 行)"
            } else {
                null
            }
        )

    private fun mergeRules(
        relPath: String,
        baseText: String,
        oursText: String,
        theirsText: String,
        oursSide: MergeSide,
        theirsSide: MergeSide,
        oursWins: Boolean
    ): FileMerge {
        val merged = MergeText.mergeRulesJson(baseText, oursText, theirsText, oursWins)
            ?: return fallback(
                relPath,
                oursText.toByteArray(Charsets.UTF_8), theirsText.toByteArray(Charsets.UTF_8),
                oursSide, theirsSide
            )
        return FileMerge(relPath, merged.toByteArray(Charsets.UTF_8))
    }

    private fun deleteWithKept(relPath: String, modified: ByteArray?, side: MergeSide, reason: String): FileMerge {
        if (modified == null) return FileMerge(relPath, null)
        val kept = keptPath(relPath, side.deviceId)
        return FileMerge(
            relPath = relPath,
            content = null,
            extras = listOf(MergeExtra(kept, modified)),
            conflict = MergeConflict(relPath, 0, countLines(modified), reason),
            inboxNote = "- $relPath — $reason (${side.display}): 修改方内容已保留为 $kept"
        )
    }

    private fun fallback(
        relPath: String,
        ours: ByteArray,
        theirs: ByteArray,
        oursSide: MergeSide,
        theirsSide: MergeSide
    ): FileMerge {
        val oursWins = oursSide >= theirsSide
        val winner = if (oursWins) ours else theirs
        val loser = if (oursWins) theirs else ours
        val loserSide = if (oursWins) theirsSide else oursSide
        val backup = conflictPath(relPath, loserSide.deviceId)
        return FileMerge(
            relPath = relPath,
            content = winner,
            extras = listOf(MergeExtra(backup, loser)),
            conflict = MergeConflict(relPath, 0, countLines(loser), "非 UTF-8 或 >256KB 兜底"),
            inboxNote = "- $relPath — 大文件/非 UTF-8, 取较新一方, 另一方已存为 $backup"
        )
    }

    private fun countLines(bytes: ByteArray?): Int {
        val text = bytes?.let { MergeText.decode(it) } ?: return 0
        return TextDiff.splitLines(text).size
    }

    private fun isMergeable(bytes: ByteArray): Boolean =
        bytes.size <= MAX_MERGE_BYTES && MergeText.isUtf8(bytes)

    private fun bytesEqual(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }

    private fun safeDevice(device: String): String {
        val cleaned = device.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return cleaned.ifBlank { "peer" }.take(40)
    }
}

/** 数据类别 (docs/memory-git-sync-design.md §2.1)。 */
enum class TwinFileClass {
    /** 长驻文档: 三方合并 (diff3)。 */
    L,
    /** 中期账本: 只增, 追加并集。 */
    M,
    /** 设备库存: 单写者。 */
    D,
    /** `twin-model-rules.json`: 按 rule.id 并集。 */
    RULES,
    /** 短期数据: 不参与同步。 */
    S
}

/**
 * 合并排序键 (lamport, deviceId) — §5.4。`deviceId` 决定排序与"同设备双写"判定,
 * `label` 只用于冲突标记/提示里的**人读设备名** (缺省回落 deviceId)。
 * `lamport` 取该侧快照时钟; 无快照时为 0 (此时退化为设备 id 排序, 仍确定)。
 */
data class MergeSide(
    val deviceId: String,
    val lamport: Long,
    val label: String = ""
) : Comparable<MergeSide> {

    /** 冲突标记/提示里展示的名字。 */
    val display: String get() = label.trim().ifBlank { deviceId }

    override fun compareTo(other: MergeSide): Int =
        compareValuesBy(this, other, { it.lamport }, { it.deviceId })
}

/** 一个文件的合并结果。`content == null` = 删除原路径。 */
data class FileMerge(
    val relPath: String,
    val content: ByteArray?,
    /** 额外落盘 (`.kept-` / `.conflict.` 备份)。 */
    val extras: List<MergeExtra> = emptyList(),
    /** 冲突记录 (行级冲突 / 删除-修改 / 兜底)。 */
    val conflict: MergeConflict? = null,
    /** 需暴露给用户的告警 (如 D 类双写)。 */
    val warning: String? = null,
    /** 需要写入 inbox 的提示行。 */
    val inboxNote: String? = null
)

/** 额外落盘文件。 */
data class MergeExtra(val relPath: String, val bytes: ByteArray)

/** 冲突记录 (inbox 报告与 `twin.status` 用)。 */
data class MergeConflict(
    val relPath: String,
    val hunks: Int,
    val lines: Int,
    val reason: String
)

/** 合并产生的一次写入。 */
data class MergeWrite(val relPath: String, val bytes: ByteArray, val kind: MergeWriteKind)

/** 写入种类 — 调用方据此决定是否触发提示词缓存失效 / 计数。 */
enum class MergeWriteKind { CONTENT, KEPT, CONFLICT_BACKUP }

/** 整个文件集的合并结果。 */
data class MergeOutcome(val files: List<FileMerge>) {

    /** 按路径排序的写入 (原路径内容 + 额外备份)。 */
    val writes: List<MergeWrite> = files.flatMap { file ->
        buildList {
            file.content?.let { add(MergeWrite(file.relPath, it, MergeWriteKind.CONTENT)) }
            file.extras.forEach { extra ->
                val kind = if (extra.relPath.contains(".conflict.")) {
                    MergeWriteKind.CONFLICT_BACKUP
                } else {
                    MergeWriteKind.KEPT
                }
                add(MergeWrite(extra.relPath, extra.bytes, kind))
            }
        }
    }

    /** 需要删除的原路径。 */
    val deletes: List<String> = files.filter { it.content == null }.map { it.relPath }

    val conflicts: List<MergeConflict> = files.mapNotNull { it.conflict }

    val warnings: List<String> = files.mapNotNull { it.warning }

    val inboxNotes: List<String> = files.mapNotNull { it.inboxNote }
}
