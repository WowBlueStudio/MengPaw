// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 框架反馈状态机测试 (v0.34.3 P2-9): report 落盘带 new 状态 → ls 可见 → mark 闭环。 */
class EvolutionFeedbackTest {

    private val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_feedback_${System.nanoTime()}"

    @Before
    fun setup() {
        DataPaths.initialize(tmp)
        EvolutionStore.resetFailuresForTest()
    }

    @Test
    fun `report then feedback ls then mark cycles status`() = runBlocking {
        val ctx = ExecutionContext(sessionId = "s1", agentName = "agent-x")

        // report → 落盘带 status: new
        val report = EvolutionEngine.executeCommand("report", listOf("测试框架缺陷描述", "--context", "cmd"), ctx)
        assertTrue("report 应成功", report?.success == true)
        val dir = File(DataPaths.evolutionFeedbackDir("agent-x"))
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("反馈文件应落盘", files.isNotEmpty())
        assertTrue("落盘文件应含 status: new", files.first().readText().contains("status: new"))

        // ls → 显示 new
        val ls = EvolutionEngine.executeCommand("feedback", listOf("ls"), ctx)
        assertTrue("ls 应显示 new 状态: ${ls?.output}", ls?.output?.contains("new") == true)

        // mark → ack, 再 ls 显示 ack
        val name = files.first().name
        val mark = EvolutionEngine.executeCommand("feedback", listOf("mark", name, "ack"), ctx)
        assertTrue("mark 应成功: ${mark?.output}", mark?.success == true)
        val ls2 = EvolutionEngine.executeCommand("feedback", listOf("ls"), ctx)
        assertTrue("ls 应显示 ack: ${ls2?.output}", ls2?.output?.contains("[ack]") == true)

        // 非法状态拒绝
        val bad = EvolutionEngine.executeCommand("feedback", listOf("mark", name, "wat"), ctx)
        assertTrue("非法状态应拒绝", bad?.success == false)
    }
}
