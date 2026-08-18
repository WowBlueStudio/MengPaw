// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

/**
 * 无障碍控件树快照 — 把 Android [android.view.accessibility.AccessibilityNodeInfo]
 * 转换为平台无关的 [AccessibilityNodeData], 再序列化为紧凑 JSON 供 Agent 解析。
 *
 * 截断策略 (防上下文膨胀): maxDepth 限制树深, maxNodes 限制总节点数,
 * maxTextLen 限制单节点文本 — 超限即停止, 输出带 truncated 标记。
 * 纯 JVM 逻辑 (不触达 Android API), 供 AccessibilitySnapshotTest 单测。
 */
internal data class AccessibilityNodeData(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val viewId: String? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focusable: Boolean = false,
    val children: List<AccessibilityNodeData> = emptyList(),
) {
    val bounds: List<Int> get() = listOf(left, top, right, bottom)
}

internal object AccessibilitySnapshot {

    /** 序列化控件树为 JSON: {"nodes":[...], "count":N, "truncated":bool} */
    fun serialize(
        root: AccessibilityNodeData,
        maxDepth: Int = 6,
        maxNodes: Int = 150,
        maxTextLen: Int = 120,
    ): String {
        val counter = Counter(maxNodes)
        val sb = StringBuilder(1024)
        sb.append("{\"nodes\":[")
        appendNode(sb, root, 0, maxDepth, maxTextLen, counter)
        sb.append("],\"count\":").append(counter.count)
        sb.append(",\"truncated\":").append(counter.truncated).append('}')
        return sb.toString()
    }

    /** 在树中查找第一个满足条件的节点 (先序), 未找到返回 null。 */
    fun findFirst(
        root: AccessibilityNodeData,
        predicate: (AccessibilityNodeData) -> Boolean,
    ): AccessibilityNodeData? {
        if (predicate(root)) return root
        for (child in root.children) {
            findFirst(child, predicate)?.let { return it }
        }
        return null
    }

    private fun appendNode(
        sb: StringBuilder,
        node: AccessibilityNodeData,
        depth: Int,
        maxDepth: Int,
        maxTextLen: Int,
        counter: Counter,
    ) {
        if (counter.exhausted) return
        if (counter.count > 0) sb.append(',')
        counter.increment()
        sb.append('{')
        field(sb, "text", node.text, maxTextLen)
        field(sb, "desc", node.contentDescription, maxTextLen)
        field(sb, "class", node.className, maxTextLen)
        field(sb, "pkg", node.packageName, maxTextLen)
        field(sb, "id", node.viewId, maxTextLen)
        sb.append("\"bounds\":[").append(node.left).append(',')
            .append(node.top).append(',').append(node.right).append(',')
            .append(node.bottom).append(']')
        flag(sb, "clickable", node.clickable)
        flag(sb, "longClickable", node.longClickable)
        flag(sb, "scrollable", node.scrollable)
        flag(sb, "checked", node.checked)
        flag(sb, "selected", node.selected)
        flag(sb, "focusable", node.focusable)
        if (node.children.isNotEmpty() && depth < maxDepth) {
            sb.append(",\"children\":[")
            var first = true
            for (child in node.children) {
                if (counter.exhausted) {
                    counter.truncated = true
                    break
                }
                if (!first) sb.append(',')
                first = false
                appendNode(sb, child, depth + 1, maxDepth, maxTextLen, counter)
            }
            sb.append(']')
        } else if (node.children.isNotEmpty()) {
            counter.truncated = true
        }
        sb.append('}')
    }

    private fun field(sb: StringBuilder, key: String, value: String?, maxTextLen: Int) {
        if (value.isNullOrEmpty()) return
        sb.append('"').append(key).append("\":\"")
            .append(escape(if (value.length > maxTextLen) value.take(maxTextLen) + "…" else value))
            .append("\",")
    }

    private fun flag(sb: StringBuilder, key: String, value: Boolean) {
        if (value) sb.append("\"").append(key).append("\":true,")
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private class Counter(private val maxNodes: Int) {
        var count: Int = 0
            private set
        var truncated: Boolean = false
        val exhausted: Boolean get() = count >= maxNodes

        fun increment() {
            count++
        }
    }
}
