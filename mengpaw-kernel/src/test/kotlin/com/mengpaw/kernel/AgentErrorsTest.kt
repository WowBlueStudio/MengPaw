// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.llm.PromptEngine
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地化错误文案回归 (P2-1): 循环检测阈值文案须与 LoopDetector 实际阈值 (5) 一致,
 * 避免"报错说 3+ 次但实际 5 次才触发"的误导。
 */
class AgentErrorsTest {

    @Test
    fun `loop detected message matches actual threshold`() {
        val zh = localizedError("loop_detected", "fs.cat /a", PromptEngine.AgentLanguage.CHINESE)
        assertTrue("中文应写明 5+ 次", zh.contains("5+"))
        val en = localizedError("loop_detected", "fs.cat /a", PromptEngine.AgentLanguage.ENGLISH)
        assertTrue("英文应写明 5+ 次", en.contains("5+"))
    }

    @Test
    fun `loop detected message carries the command detail`() {
        val zh = localizedError("loop_detected", "agent.ls", PromptEngine.AgentLanguage.CHINESE)
        assertTrue("应包含触发命令", zh.contains("agent.ls"))
    }
}
