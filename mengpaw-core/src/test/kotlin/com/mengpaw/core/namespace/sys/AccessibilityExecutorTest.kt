// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** sys.accessibility.* 参数解析测试 (纯 JVM) — click/swipe 的位置参数与 --flag 解析。 */
class AccessibilityExecutorTest {

    @Test
    fun click解析坐标() {
        val target = AccessibilityExecutor.parseClick(listOf("120", "340"))
        assertEquals(120, target?.x)
        assertEquals(340, target?.y)
        assertNull(target?.text)
        assertNull(target?.viewId)
    }

    @Test
    fun click解析text与id() {
        val byText = AccessibilityExecutor.parseClick(listOf("--text", "确定"))
        assertEquals("确定", byText?.text)
        val byId = AccessibilityExecutor.parseClick(listOf("--id", "com.app:id/ok"))
        assertEquals("com.app:id/ok", byId?.viewId)
    }

    @Test
    fun click解析text与id带等号分隔() {
        val byText = AccessibilityExecutor.parseClick(listOf("--text=确定"))
        assertEquals("确定", byText?.text)
        val byId = AccessibilityExecutor.parseClick(listOf("--id=com.app:id/ok"))
        assertEquals("com.app:id/ok", byId?.viewId)
    }

    @Test
    fun click非法参数返回null() {
        assertNull(AccessibilityExecutor.parseClick(emptyList()))
        assertNull(AccessibilityExecutor.parseClick(listOf("abc", "def")))
        assertNull(AccessibilityExecutor.parseClick(listOf("100")))
        assertNull(AccessibilityExecutor.parseClick(listOf("--text")))
    }

    @Test
    fun swipe解析坐标与时长() {
        val spec = AccessibilityExecutor.parseSwipe(listOf("0", "0", "300", "600", "--duration", "800"))
        assertEquals(0, spec?.x1)
        assertEquals(0, spec?.y1)
        assertEquals(300, spec?.x2)
        assertEquals(600, spec?.y2)
        assertEquals(800L, spec?.durationMs)
    }

    @Test
    fun swipe默认时长() {
        val spec = AccessibilityExecutor.parseSwipe(listOf("10", "20", "30", "40"))
        assertEquals(300L, spec?.durationMs)
    }

    @Test
    fun swipe非法参数返回null() {
        assertNull(AccessibilityExecutor.parseSwipe(listOf("1", "2", "3")))
        assertNull(AccessibilityExecutor.parseSwipe(listOf("a", "b", "c", "d")))
    }
}
