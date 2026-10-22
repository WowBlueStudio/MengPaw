// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

/**
 * 文本 diff — Myers 最短编辑脚本 + unified 渲染 (P1, `memory.diff` 用)。
 *
 * 设计约束:
 * - **CRLF 归一**: 输入先统一为 LF 再比较 (项目 CRLF 教训 — 否则每行都被判变更)。
 * - **中文安全**: 按行比较, 不做字节切分, 中文标点不会切碎。
 * - **空文件**: 空↔非空输出 `@@ -0,0 +1,N @@`, 不产生伪行。
 *
 * 输出格式与 `git diff -u` 一致 (`--- a/x` / `+++ b/x` / `@@ -a,b +c,d @@`), 便于人读。
 */
object TextDiff {

    /** 一个编辑操作: ' ' 保留 / '-' 删除 / '+' 新增。 */
    private data class Edit(val kind: Char, val text: String)

    /** 渲染后的行: 带旧/新行号 (1-based)。 */
    private data class Op(val kind: Char, val text: String, val oldNo: Int, val newNo: Int)

    /**
     * 生成 unified diff。
     *
     * @return 无差异时返回空串; 否则含文件头与 hunk 的完整 diff
     */
    fun diff(
        oldText: String,
        newText: String,
        oldName: String = "a",
        newName: String = "b",
        context: Int = 3
    ): String {
        val a = splitLines(oldText)
        val b = splitLines(newText)
        val ops = numberOps(myers(a, b))
        val changes = ops.indices.filter { ops[it].kind != ' ' }
        if (changes.isEmpty()) return ""

        return buildString {
            appendLine("--- $oldName")
            appendLine("+++ $newName")
            hunkRanges(changes, ops.size, context).forEach { range ->
                appendHunk(ops.subList(range.first, range.last + 1))
            }
        }
    }

    /** 换行归一: CRLF / 孤立 CR → LF (不改动内容字符)。 */
    fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /** 按行切分; 末尾换行不产生多余空行。 */
    fun splitLines(text: String): List<String> {
        val normalized = normalizeNewlines(text)
        if (normalized.isEmpty()) return emptyList()
        val lines = normalized.split('\n')
        return if (lines.lastOrNull()?.isEmpty() == true) lines.dropLast(1) else lines
    }

    // ── Myers 最短编辑脚本 ─────────────────────────────────────────

    private fun myers(a: List<String>, b: List<String>): List<Edit> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        val max = n + m
        val offset = max
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var foundD = 0
        var found = false

        outer@ for (d in 0..max) {
            for (k in -d..d step 2) {
                val idx = k + offset
                var x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) v[idx + 1] else v[idx - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[idx] = x
                if (x >= n && y >= m) { foundD = d; found = true; break@outer }
            }
            trace.add(v.copyOf())
        }
        if (!found) return emptyList()
        trace.add(v.copyOf()) // 收尾: 补上命中 d 的 v 快照 (循环内 break 跳过了) 

        if (foundD == 0) return emptyList()

        val edits = ArrayDeque<Edit>()
        var x = n
        var y = m
        for (d in foundD downTo 0) {
            val vv = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv[k - 1 + offset] < vv[k + 1 + offset])) k + 1 else k - 1
            val prevX = vv[prevK + offset]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                edits.addFirst(Edit(' ', a[x - 1]))
                x--
                y--
            }
            if (d > 0) {
                if (x == prevX) {
                    edits.addFirst(Edit('+', b[y - 1]))
                    y--
                } else {
                    edits.addFirst(Edit('-', a[x - 1]))
                    x--
                }
            }
        }
        return edits.toList()
    }

    // ── unified 渲染 ────────────────────────────────────────────────

    private fun numberOps(edits: List<Edit>): List<Op> {
        val ops = ArrayList<Op>(edits.size)
        var oldNo = 1
        var newNo = 1
        for (e in edits) {
            when (e.kind) {
                ' ' -> { ops.add(Op(' ', e.text, oldNo, newNo)); oldNo++; newNo++ }
                '-' -> { ops.add(Op('-', e.text, oldNo, newNo)); oldNo++ }
                '+' -> { ops.add(Op('+', e.text, oldNo, newNo)); newNo++ }
            }
        }
        return ops
    }

    private fun hunkRanges(changes: List<Int>, size: Int, context: Int): List<IntRange> {
        if (changes.isEmpty() || size == 0) return emptyList()
        val ctx = context.coerceAtLeast(0)
        val ranges = mutableListOf<IntRange>()
        var start = (changes.first() - ctx).coerceAtLeast(0)
        var end = (changes.first() + ctx).coerceAtMost(size - 1)
        for (idx in changes.drop(1)) {
            val s = (idx - ctx).coerceAtLeast(0)
            val e = (idx + ctx).coerceAtMost(size - 1)
            if (s <= end + 1) {
                end = maxOf(end, e)
            } else {
                ranges.add(start..end)
                start = s
                end = e
            }
        }
        ranges.add(start..end)
        return ranges
    }

    private fun StringBuilder.appendHunk(ops: List<Op>) {
        if (ops.isEmpty()) return
        val oldCount = ops.count { it.kind != '+' }
        val newCount = ops.count { it.kind != '-' }
        val oldStart = if (oldCount == 0) ops.first().oldNo - 1 else ops.first().oldNo
        val newStart = if (newCount == 0) ops.first().newNo - 1 else ops.first().newNo
        appendLine("@@ -$oldStart,$oldCount +$newStart,$newCount @@")
        for (op in ops) {
            append(op.kind).append(op.text).append('\n')
        }
    }
}
