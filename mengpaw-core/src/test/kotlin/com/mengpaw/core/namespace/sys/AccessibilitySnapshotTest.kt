// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍控件树快照序列化测试 (纯 JVM) — 字段输出 / 深度·节点数·文本截断 / JSON 转义 / findFirst。
 */
class AccessibilitySnapshotTest {

    private fun node(
        text: String? = null,
        clickable: Boolean = false,
        children: List<AccessibilityNodeData> = emptyList(),
    ) = AccessibilityNodeData(
        text = text,
        className = "android.widget.Button",
        packageName = "com.example",
        left = 10,
        top = 20,
        right = 110,
        bottom = 60,
        clickable = clickable,
        children = children,
    )

    @Test
    fun 序列化输出核心字段与节点数() {
        val root = node(text = "确定", clickable = true, children = listOf(node(text = "子项")))
        val json = AccessibilitySnapshot.serialize(root)
        assertTrue("应含文本", json.contains("\"text\":\"确定\""))
        assertTrue("应含 clickable", json.contains("\"clickable\":true"))
        assertTrue("应含 bounds", json.contains("\"bounds\":[10,20,110,60]"))
        assertTrue("应含类名", json.contains("\"class\":\"android.widget.Button\""))
        assertTrue("应含包名", json.contains("\"pkg\":\"com.example\""))
        assertTrue("应含子节点", json.contains("\"children\":"))
        assertTrue("count 应为 2", json.contains("\"count\":2"))
        assertTrue("不应截断", json.contains("\"truncated\":false"))
    }

    @Test
    fun 节点数超限时截断并标记() {
        val root = node(children = (1..10).map { node(text = "n$it") })
        val json = AccessibilitySnapshot.serialize(root, maxNodes = 4)
        assertTrue("count 应为 4", json.contains("\"count\":4"))
        assertTrue("应标记截断", json.contains("\"truncated\":true"))
        assertFalse("超出限额的节点不应出现", json.contains("\"n5\""))
    }

    @Test
    fun 深度超限时省略children并标记() {
        val root = node(text = "root", children = listOf(node(text = "child", children = listOf(node(text = "grand")))))
        val json = AccessibilitySnapshot.serialize(root, maxDepth = 1)
        assertTrue("root 应在", json.contains("\"text\":\"root\""))
        assertTrue("child 应在", json.contains("\"text\":\"child\""))
        assertFalse("grand 不应在 (深度截断)", json.contains("\"grand\""))
        assertTrue("应标记截断", json.contains("\"truncated\":true"))
    }

    @Test
    fun 超长文本截断加省略号() {
        val longText = "a".repeat(200)
        val json = AccessibilitySnapshot.serialize(node(text = longText), maxTextLen = 8)
        assertTrue("应保留前 8 字符", json.contains("\"text\":\"aaaaaaaa…\""))
        assertFalse("不应含完整文本", json.contains("\"text\":\"${longText}\""))
    }

    @Test
    fun JSON转义引号换行与反斜杠() {
        val root = node(text = "say \"hi\"\npath\\x")
        val json = AccessibilitySnapshot.serialize(root)
        assertTrue("引号应转义", json.contains("say \\\"hi\\\""))
        assertTrue("换行应转义", json.contains("\\n"))
        assertTrue("反斜杠应转义", json.contains("\\\\"))
    }

    @Test
    fun findFirst按文本查找节点() {
        val root = node(text = "root", children = listOf(
            node(text = "a"),
            node(text = "b", children = listOf(node(text = "c", clickable = true))),
        ))
        val found = AccessibilitySnapshot.findFirst(root) { it.text == "c" }
        assertEquals("c", found?.text)
        assertTrue(found?.clickable == true)
        assertNull(AccessibilitySnapshot.findFirst(root) { it.text == "不存在" })
    }
}
