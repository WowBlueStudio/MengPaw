// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 三方文本合并的算法层 (P2, docs/memory-git-sync-design.md §5.2)。
 *
 * 与 [TreeMerge] 分工: [TreeMerge] 负责"按类选策略 + 落盘产物", 本对象只做纯文本变换:
 * - [diff3] — 行级三方合并, 冲突渲染 `<<<<<<< ours (设备)` / `=======` / `>>>>>>> theirs (设备)`
 * - [mergeFrontmatterAndBody] — frontmatter 键级合并 (同键按 (lamport, deviceId) 取一方) + 正文 diff3
 * - [appendUnion] — M 类追加并集 (按 (lamport, deviceId) 排序)
 * - [mergeRulesJson] — `twin-model-rules.json` 按 `rule.id` 并集
 *
 * 不变量: 输入先做 CRLF/CR → LF 归一, 输出统一 LF (项目 CRLF 教训: 否则每行都被判变更)。
 */
internal object MergeText {

    private const val OURS_MARK = "<<<<<<< ours"
    private const val THEIRS_MARK = ">>>>>>> theirs"
    private const val SEPARATOR = "======="
    private val JSON = Json { ignoreUnknownKeys = true }
    private val KEY = Regex("^([A-Za-z0-9_.-]+):.*$")

    /** 严格 UTF-8 校验 (非 UTF-8 → 走兜底分支, 不猜编码)。 */
    fun isUtf8(bytes: ByteArray): Boolean = decode(bytes) != null

    /** 严格 UTF-8 解码; 非法字节 → null。 */
    fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: Exception) {
        null
    }

    // ── diff3 ──────────────────────────────────────────────────────

    /** 行级三方合并。冲突块内保留双方原文, 计数供 inbox/报告使用。 */
    fun diff3(base: String, ours: String, theirs: String, oursLabel: String, theirsLabel: String): TextMerge {
        val baseLines = TextDiff.splitLines(base)
        val oursLines = TextDiff.splitLines(ours)
        val theirsLines = TextDiff.splitLines(theirs)
        val oursChanges = TextDiff.lineChanges(base, ours)
        val theirsChanges = TextDiff.lineChanges(base, theirs)
        val out = ArrayList<String>(baseLines.size + oursLines.size)
        var baseNo = 0
        var i = 0
        var j = 0
        var hunks = 0
        var conflictLines = 0

        while (i < oursChanges.size || j < theirsChanges.size) {
            val nextOurs = oursChanges.getOrNull(i)
            val nextTheirs = theirsChanges.getOrNull(j)
            val start = minOf(nextOurs?.baseStart ?: Int.MAX_VALUE, nextTheirs?.baseStart ?: Int.MAX_VALUE)
            if (start == Int.MAX_VALUE) break
            if (start > baseNo) {
                out.addAll(baseLines.subList(baseNo, start))
                baseNo = start
            }
            // 吸收与本区间相交/相接的变更 (insert at same pos 也算重叠)
            var end = start
            val oursGroup = ArrayList<TextDiff.LineChange>()
            val theirsGroup = ArrayList<TextDiff.LineChange>()
            var grew = true
            while (grew) {
                grew = false
                while (i < oursChanges.size && oursChanges[i].baseStart <= end) {
                    oursGroup.add(oursChanges[i])
                    end = maxOf(end, oursChanges[i].baseEnd)
                    i++
                    grew = true
                }
                while (j < theirsChanges.size && theirsChanges[j].baseStart <= end) {
                    theirsGroup.add(theirsChanges[j])
                    end = maxOf(end, theirsChanges[j].baseEnd)
                    j++
                    grew = true
                }
            }
            val oursBlock = sideBlock(baseLines, oursLines, oursGroup, start, end)
            val theirsBlock = sideBlock(baseLines, theirsLines, theirsGroup, start, end)
            when {
                oursGroup.isEmpty() -> out.addAll(theirsBlock)
                theirsGroup.isEmpty() -> out.addAll(oursBlock)
                oursBlock == theirsBlock -> out.addAll(oursBlock)
                else -> {
                    out.add("$OURS_MARK ($oursLabel)")
                    out.addAll(oursBlock)
                    out.add(SEPARATOR)
                    out.addAll(theirsBlock)
                    out.add("$THEIRS_MARK ($theirsLabel)")
                    hunks++
                    conflictLines += oursBlock.size + theirsBlock.size
                }
            }
            baseNo = end
        }
        if (baseNo < baseLines.size) out.addAll(baseLines.subList(baseNo, baseLines.size))

        val trailing = base.endsWith("\n") || ours.endsWith("\n") || theirs.endsWith("\n")
        val text = if (out.isEmpty()) "" else out.joinToString("\n") + if (trailing) "\n" else ""
        return TextMerge(text, hunks, conflictLines)
    }

    /** 把一组变更应用到 base 区间 [start, end) 上, 得到该侧文本。 */
    private fun sideBlock(
        base: List<String>,
        side: List<String>,
        changes: List<TextDiff.LineChange>,
        start: Int,
        end: Int
    ): List<String> {
        val out = ArrayList<String>()
        var cursor = start
        for (change in changes) {
            if (change.baseStart == change.baseEnd) {
                // 纯插入: 先补到插入点, 再整段插入
                if (cursor < change.baseStart) {
                    out.addAll(base.subList(cursor, change.baseStart))
                    cursor = change.baseStart
                }
                if (change.otherStart < change.otherEnd) {
                    out.addAll(side.subList(change.otherStart, change.otherEnd))
                }
                continue
            }
            val from = maxOf(change.baseStart, start)
            val to = minOf(change.baseEnd, end)
            if (from >= to) continue
            if (cursor < from) {
                out.addAll(base.subList(cursor, from))
                cursor = from
            }
            out.addAll(side.subList(change.otherStart, change.otherEnd))
            cursor = maxOf(cursor, to)
        }
        if (cursor < end) out.addAll(base.subList(cursor, end))
        return out
    }

    // ── frontmatter + 正文 ─────────────────────────────────────────

    /** frontmatter 解析结果: 顶层键 → 原始行 (含缩进续行), 以及正文。 */
    data class FrontMatter(
        val entries: LinkedHashMap<String, List<String>>,
        val trailing: List<String>,
        val body: String
    )

    /** 解析 `--- … ---` 块; 无 frontmatter → null。 */
    fun parseFrontmatter(text: String): FrontMatter? {
        val lines = TextDiff.normalizeNewlines(text).split("\n")
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        var close = -1
        for (k in 1 until lines.size) {
            if (lines[k].trim() == "---") {
                close = k
                break
            }
        }
        if (close < 0) return null
        val entries = LinkedHashMap<String, MutableList<String>>()
        val trailing = mutableListOf<String>()
        var current: String? = null
        for (k in 1 until close) {
            val line = lines[k]
            if (line.isBlank()) continue
            val continuation = line.startsWith(" ") || line.startsWith("\t") || line.startsWith("- ")
            val match = KEY.matchEntire(line)
            when {
                match != null -> {
                    val key = match.groupValues[1]
                    current = key
                    entries[key] = mutableListOf(line)
                }
                continuation && current != null -> entries[current]?.add(line)
                else -> {
                    trailing.add(line)
                    current = null
                }
            }
        }
        val body = lines.drop(close + 1).joinToString("\n")
        val frozen = LinkedHashMap<String, List<String>>()
        entries.forEach { (key, value) -> frozen[key] = value.toList() }
        return FrontMatter(frozen, trailing, body)
    }

    /**
     * frontmatter 键级合并 + 正文 diff3。
     *
     * 同键双方都改 → 按 (lamport, deviceId) 取较新一方, 并在块尾写注释标注取值来源;
     * 一方删键另一方改 → 保留修改方 (永不静默删)。
     */
    fun mergeFrontmatterAndBody(
        base: String,
        ours: String,
        theirs: String,
        oursLabel: String,
        theirsLabel: String,
        oursWins: Boolean,
        oursDevice: String,
        theirsDevice: String
    ): TextMerge {
        val b = parseFrontmatter(base)
        val o = parseFrontmatter(ours)
        val t = parseFrontmatter(theirs)
        if (b == null || o == null || t == null) {
            return diff3(base, ours, theirs, oursLabel, theirsLabel)
        }
        val body = diff3(b.body, o.body, t.body, oursLabel, theirsLabel)
        val keys = LinkedHashSet<String>()
        keys.addAll(b.entries.keys)
        keys.addAll(o.entries.keys)
        keys.addAll(t.entries.keys)
        val merged = LinkedHashMap<String, List<String>>()
        val notes = mutableListOf<String>()
        for (key in keys) {
            val baseValue = b.entries[key]
            val oursValue = o.entries[key]
            val theirsValue = t.entries[key]
            val chosen: List<String>? = when {
                oursValue == theirsValue -> oursValue
                oursValue == baseValue -> theirsValue
                theirsValue == baseValue -> oursValue
                oursValue == null -> theirsValue
                theirsValue == null -> oursValue
                else -> {
                    val device = if (oursWins) oursDevice else theirsDevice
                    notes.add("# 合并取值: $key ← $device")
                    if (oursWins) oursValue else theirsValue
                }
            }
            if (chosen != null) merged[key] = chosen
        }
        val sb = StringBuilder("---\n")
        merged.values.forEach { lines -> lines.forEach { sb.append(it).append('\n') } }
        b.trailing.forEach { sb.append(it).append('\n') }
        notes.forEach { sb.append(it).append('\n') }
        sb.append("---\n")
        sb.append(body.text)
        return TextMerge(sb.toString(), body.hunks, body.conflictLines)
    }

    // ── M 类追加并集 ───────────────────────────────────────────────

    /**
     * 追加并集: 两侧都必须是 base 的"纯追加" (base 是前缀), 否则返回 null
     * (调用方回退 diff3)。较早一侧的块在前 — 排序键 (lamport, deviceId)。
     */
    fun appendUnion(base: String, ours: String, theirs: String, oursFirst: Boolean): TextMerge? {
        val baseLines = TextDiff.splitLines(base)
        val oursLines = TextDiff.splitLines(ours)
        val theirsLines = TextDiff.splitLines(theirs)
        if (!startsWith(oursLines, baseLines) || !startsWith(theirsLines, baseLines)) return null
        val oursExtra = oursLines.drop(baseLines.size)
        val theirsExtra = theirsLines.drop(baseLines.size)
        val added = when {
            oursExtra == theirsExtra -> oursExtra
            oursFirst -> oursExtra + theirsExtra
            else -> theirsExtra + oursExtra
        }
        val lines = baseLines + added
        val trailing = base.endsWith("\n") || ours.endsWith("\n") || theirs.endsWith("\n")
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n") + if (trailing) "\n" else ""
        return TextMerge(text, 0, 0)
    }

    private fun startsWith(lines: List<String>, prefix: List<String>): Boolean {
        if (lines.size < prefix.size) return false
        for (k in prefix.indices) if (lines[k] != prefix[k]) return false
        return true
    }

    // ── twin-model-rules.json ──────────────────────────────────────

    /**
     * 按 `rule.id` 并集; 同 id 按 (lamport, deviceId) 取较新一方。
     * JSON 解析失败 → null (调用方走兜底: 取较新一方 + .conflict 备份)。
     */
    fun mergeRulesJson(base: String?, ours: String, theirs: String, oursWins: Boolean): String? {
        val baseObj = parseObject(base ?: "{}") ?: JsonObject(emptyMap())
        val oursObj = parseObject(ours) ?: return null
        val theirsObj = parseObject(theirs) ?: return null
        val baseRules = rules(baseObj) ?: emptyList()
        val oursRules = rules(oursObj) ?: emptyList()
        val theirsRules = rules(theirsObj) ?: emptyList()

        val order = LinkedHashSet<String>()
        (baseRules + oursRules + theirsRules).forEach { (id, _) -> order.add(id) }
        val baseMap = baseRules.toMap()
        val oursMap = oursRules.toMap()
        val theirsMap = theirsRules.toMap()

        val merged = buildJsonArray {
            for (id in order) {
                val b = baseMap[id]
                val o = oursMap[id]
                val t = theirsMap[id]
                val chosen: JsonElement? = when {
                    o == t -> o
                    o == b -> t
                    t == b -> o
                    else -> if (oursWins) o ?: t else t ?: o
                }
                if (chosen != null) add(chosen)
            }
        }
        val skeleton = if (oursWins) oursObj else theirsObj
        val result = buildJsonObject {
            skeleton.forEach { (key, value) -> if (key != "rules") put(key, value) }
            put("rules", merged)
        }
        return result.toString()
    }

    private fun parseObject(text: String): JsonObject? = try {
        JSON.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** 规则数组 → (稳定 key, 原始 JSON)。缺 id 的条目用序号当 key (两侧各自保留)。 */
    private fun rules(obj: JsonObject): List<Pair<String, JsonElement>>? {
        val array = obj["rules"] as? JsonArray ?: return emptyList()
        val out = ArrayList<Pair<String, JsonElement>>(array.size)
        array.forEachIndexed { index, element ->
            val id = (element as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            out.add((id ?: "#$index") to element)
        }
        return out
    }
}

/** 三方文本合并结果。`hunks` = 冲突块数, `conflictLines` = 冲突出入行数 (inbox 报告用)。 */
data class TextMerge(val text: String, val hunks: Int, val conflictLines: Int) {
    val hasConflict: Boolean get() = hunks > 0
}
