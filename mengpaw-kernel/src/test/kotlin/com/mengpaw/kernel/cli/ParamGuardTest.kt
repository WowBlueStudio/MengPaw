// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 命令参数歧义防护测试 (v0.34.3 全量审计): 污染提示 + 多余参数提示。 */
class ParamGuardTest {

    @Test
    fun `polluted hint fires on trailing description word`() {
        val h = ParamGuard.pollutedHint(listOf("2026-08-09", "等待结果"), "agent.memory.mid.rm")
        assertNotNull("应检测污染", h)
        assertTrue("应含污染提示字样", h!!.contains("参数污染"))
        assertTrue("应给纯净重发指引", h.contains("agent.memory.mid.rm 2026-08-09"))
    }

    @Test
    fun `polluted hint not fired for single arg or real filename`() {
        assertNull("单参数不提示", ParamGuard.pollutedHint(listOf("2026-08-09"), "agent.memory.mid.rm"))
        assertNull("文件名含描述词后缀不误报", ParamGuard.pollutedHint(listOf("2026-08-09", "结果.txt"), "agent.memory.mid.rm"))
        // "结果.txt" 前缀命中但长度超限 — 视为合法文件名
    }

    @Test
    fun `extra args hint fires only when over expected`() {
        val h = ParamGuard.extraArgsHint(listOf("/a", "/b", "等待结果"), 2, "net.curl")
        assertNotNull("多余参数应提示", h)
        assertTrue("应指出多余片段", h!!.contains("等待结果"))
        assertNull("参数恰好不提示", ParamGuard.extraArgsHint(listOf("/a", "/b"), 2, "net.curl"))
        assertNull("参数不足不提示", ParamGuard.extraArgsHint(listOf("/a"), 2, "net.curl"))
    }
}
