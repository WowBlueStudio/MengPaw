// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * SettingsDialogs.kt 的 newTriggerId 测试 (P2 修复: 随机 4 位 ID → 时间戳 + AtomicLong 单调计数器)。
 *
 * 说明: 函数为文件级 private — 经反射调用文件门面类 (SettingsDialogsKt) 的静态方法;
 * 门面类静态初始化仅创建 AtomicLong(0), 纯 JVM。
 *
 * 覆盖: ID 前缀正确、格式 prefix-时间戳-序号、同前缀连续调用不重复、批量无碰撞 (按 id 去重的触发器防撞)。
 */
class SettingsDialogsKtTest {

    /** 反射调用 private 顶层函数 newTriggerId。 */
    private fun newTriggerId(prefix: String): String {
        val clazz = Class.forName("com.mengpaw.shell.ui.screens.SettingsDialogsKt")
        val method = clazz.getDeclaredMethod("newTriggerId", String::class.java)
        method.isAccessible = true
        return method.invoke(null, prefix) as String
    }

    @Test
    fun ID以指定前缀开头() {
        assertTrue("cron 前缀", newTriggerId("cron").startsWith("cron-"))
        assertTrue("schedule 前缀", newTriggerId("schedule").startsWith("schedule-"))
        assertTrue("bk 前缀", newTriggerId("bk").startsWith("bk-"))
    }

    @Test
    fun ID格式为前缀_时间戳_序号() {
        val id = newTriggerId("cron")
        assertTrue(
            "ID 格式应为 prefix-<时间戳>-<单调序号>, 实际: $id",
            ID_PATTERN.matcher(id).matches()
        )
    }

    @Test
    fun 同前缀连续两次调用ID不同() {
        assertFalse("同毫秒连续调用也不得重复", newTriggerId("cron") == newTriggerId("cron"))
    }

    @Test
    fun 批量生成无碰撞() {
        // 触发器按 id 去重/增删 — 200 次批量生成必须全唯一
        val ids = (0 until 200).map { newTriggerId("cron") }
        assertEquals("批量生成不得碰撞", 200, ids.toSet().size)
    }

    @Test
    fun 不同前缀ID不同() {
        assertFalse(newTriggerId("a") == newTriggerId("b"))
    }

    @Test
    fun 序号单调递增() {
        // 尾部序号为 AtomicLong.incrementAndGet — 严格递增
        val seqA = newTriggerId("cron").substringAfterLast('-').toLong()
        val seqB = newTriggerId("cron").substringAfterLast('-').toLong()
        assertTrue("序号必须递增: $seqA → $seqB", seqB > seqA)
    }

    companion object {
        private val ID_PATTERN = Pattern.compile("^cron-\\d+-\\d+$")
    }
}
