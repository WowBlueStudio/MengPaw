// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoopDetector 循环检测回归测试 (P1-3/P2-2):
 * 三通道 — 精确串重复 / 命令名等价变体 / 周期2交替; 安全命令豁免。
 */
class LoopDetectorTest {

    @Test
    fun `exact repeat triggers at threshold`() {
        val d = LoopDetector()
        repeat(4) { assertFalse("前 4 次不得触发", d.detectLoop("fs.cat /test")) }
        assertTrue("第 5 次应触发", d.detectLoop("fs.cat /test"))
    }

    @Test
    fun `name-level variant caught even when exact repeat below threshold`() {
        // `fs.cat /a` vs `fs.cat -f /a` — 完整串均不足 5, 但命令名 (首 token) 累积 >=5 应触发
        val d = LoopDetector()
        val variants = listOf("fs.cat /a", "fs.cat /b", "fs.cat -f /a", "fs.cat --all", "fs.cat .")
        variants.forEach { d.detectLoop(it) } // 第 5 个命令名 "fs.cat" 计数 = 5
        assertTrue("命令名等价变体应触发循环检测", d.detectLoop("fs.cat /x"))
    }

    @Test
    fun `two-command alternation caught before either repeats enough`() {
        // A/B 交替 (两条非安全命令): A、B 各自出现次数低于 exact/name 阈值,
        // 但窗口内恰好两种命令且无相邻重复 → 触发周期2交替
        val d = LoopDetector()
        repeat(3) {
            d.detectLoop("fs.cat /a")
            d.detectLoop("fs.ls /b")
        }
        // 第 7 次调用后窗口含 A,B,A,B,A,B,A 共 7 个, 两种命令无相邻重复
        assertTrue("A/B 交替假循环应触发", d.detectLoop("fs.cat /a"))
    }

    @Test
    fun `legitimate mixed commands never trigger`() {
        val d = LoopDetector()
        val seq = listOf("agent.ls docs", "agent.docs", "self.status", "agent.ls .", "agent.memory")
        repeat(3) { seq.forEach { d.detectLoop(it) } }
        assertFalse("多命令混合工作不应误判循环", d.detectLoop("self.status"))
    }

    @Test
    fun `safe read commands never trigger`() {
        val d = LoopDetector()
        repeat(10) {
            assertFalse(d.detectLoop("agent.docs"))
            assertFalse(d.detectLoop("agent.memory 测试"))
            assertFalse(d.detectLoop("self.version"))
        }
    }

    @Test
    fun `trackResult flags consecutive failures`() {
        val d = LoopDetector()
        repeat(4) { assertFalse(d.trackResult(false)) }
        assertTrue("连续 5 次失败应触发", d.trackResult(false))
        d.reset()
        assertFalse("reset 后重新计数", d.trackResult(false))
        assertFalse("成功清零", d.trackResult(true))
        repeat(4) { assertFalse(d.trackResult(false)) }
        assertTrue(d.trackResult(false))
    }
}
