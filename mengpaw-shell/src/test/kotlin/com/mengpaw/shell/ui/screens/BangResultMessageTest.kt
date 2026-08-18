// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.cli.ExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * bang 结果气泡规则测试 (v0.42.3 用户定案):
 * 空值不返回 / 成功=输出文本 (灰) / 失败=错误文本 (红) / 超长截断。
 */
class BangResultMessageTest {

    @Test
    fun `成功且输出为空时不返回气泡`() {
        assertNull(bangResultMessage(ExecutionResult.ok("")))
        assertNull(bangResultMessage(ExecutionResult.ok("   \n  ")))
    }

    @Test
    fun `成功且有输出时返回输出文本`() {
        assertEquals("下载完成", bangResultMessage(ExecutionResult.ok("下载完成")))
        // 输出首尾空白保留语义, 仅用于空判定
        assertEquals(" 下载完成 ", bangResultMessage(ExecutionResult.ok(" 下载完成 ")))
    }

    @Test
    fun `失败时返回错误文本并兜底`() {
        assertEquals("网络不可达", bangResultMessage(ExecutionResult.fail("网络不可达")))
        assertEquals("命令执行失败", bangResultMessage(ExecutionResult.fail("", code = 1)))
    }

    @Test
    fun `超长输出截断到4000字符`() {
        val long = "a".repeat(5000)
        val msg = bangResultMessage(ExecutionResult.ok(long))
        assertEquals(4000 + "\n\n...(输出过长, 已截断)".length, msg!!.length)
        assert(msg.startsWith("a".repeat(4000)))
    }
}
