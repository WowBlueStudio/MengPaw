// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本 diff 测试 — 空文件、仅末行变更、CRLF 归一、中文文本。
 */
class TextDiffTest {

    @Test
    fun `两边都为空返回空串`() {
        assertTrue(TextDiff.diff("", "", "a/x", "b/x").isEmpty())
        assertTrue(TextDiff.diff("same\n", "same\n").isEmpty())
    }

    @Test
    fun `空文件到有内容`() {
        val d = TextDiff.diff("", "第一行\n第二行\n", "a/soul.md", "b/soul.md")
        assertTrue("应含空侧 hunk 头: $d", d.contains("@@ -0,0 +1,2 @@"))
        assertTrue(d.contains("+第一行"))
        assertTrue(d.contains("+第二行"))
        assertTrue(d.contains("--- a/soul.md"))
        assertTrue(d.contains("+++ b/soul.md"))
    }

    @Test
    fun `仅末行变更保留上文上下文`() {
        val old = "标题\n\n正文甲\n正文乙\n"
        val new = "标题\n\n正文甲\n正文丙\n"
        val d = TextDiff.diff(old, new)
        assertTrue(d.contains("@@ -1,4 +1,4 @@"))
        assertTrue(d.contains("-正文乙"))
        assertTrue(d.contains("+正文丙"))
        assertFalse("未变更的行不应出现在删除侧", d.contains("-正文甲"))
        assertFalse("未变更的行不应出现在新增侧", d.contains("+正文甲"))
    }

    @Test
    fun `CRLF 输入先归一再比较`() {
        val old = "第一行\r\n第二行\r\n第三行\r\n"
        val new = "第一行\r\n第二行改动\r\n第三行\r\n"
        val d = TextDiff.diff(old, new)
        assertTrue("只应有一处删除: $d", d.contains("-第二行"))
        assertTrue(d.contains("+第二行改动"))
        assertFalse("CRLF 不得导致整文件重写", d.contains("-第一行") || d.contains("+第一行"))
    }

    @Test
    fun `中文标点按行比较不被切碎`() {
        val old = "他说：「今天下雨。」\n然后离开了。\n"
        val new = "他说：「今天放晴。」\n然后离开了。\n"
        val d = TextDiff.diff(old, new)
        assertTrue(d.contains("他说：「今天下雨。」"))
        assertTrue(d.contains("他说：「今天放晴。」"))
        assertTrue(d.contains(" 然后离开了。"))
    }

    @Test
    fun `多段变更合并为独立 hunk`() {
        val old = (1..20).joinToString("\n") { "行$it" } + "\n"
        val new = old.replace("行2\n", "行二\n").replace("行19\n", "行十九\n")
        val d = TextDiff.diff(old, new)
        assertTrue("应有两个独立 hunk: $d", d.split("@@").size >= 3)
        assertTrue(d.contains("-行2"))
        assertTrue(d.contains("+行二"))
        assertTrue(d.contains("-行19"))
        assertTrue(d.contains("+行十九"))
    }
}
