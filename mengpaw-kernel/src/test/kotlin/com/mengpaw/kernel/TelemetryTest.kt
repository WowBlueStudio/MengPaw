// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P2-12(自检报告): self.stats 事件流 (JSON lines) — 写入/读取 round-trip + token/耗时摘要。
 */
class TelemetryTest {

    @Before
    fun init() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_telemetry_test")
        Telemetry.eventsFile = File(DataPaths.BASE, "events.jsonl")
        Telemetry.reset()
    }

    @Test
    fun `command and llm events roundtrip with temp file`() {
        Telemetry.recordCommand("agent.read profile.md", true, 12, "MengPaw")
        Telemetry.recordCommand("proc.exec ls", false, 3, "MengPaw")  // 失败事件也记录
        Telemetry.recordLlm(100, 50, 200)

        val lines = Telemetry.tailEvents(10)
        assertEquals("3 条事件", 3, lines.size)

        // JSON lines 形态: 时间戳/类型/字段
        assertTrue("命令事件", lines[0].contains("\"type\":\"cmd\""))
        assertTrue("命令名入事件", lines[0].contains("agent.read profile.md"))
        assertTrue("agent 维度", lines[0].contains("\"agent\":\"MengPaw\""))
        assertTrue("失败命令入事件", lines[1].contains("\"ok\":false"))
        assertTrue("LLM 事件", lines[2].contains("\"type\":\"llm\""))
        assertTrue("prompt token", lines[2].contains("\"prompt\":100"))
        assertTrue("completion token", lines[2].contains("\"completion\":50"))
        assertTrue("耗时", lines[2].contains("\"ms\":200"))
    }

    @Test
    fun `sanitizer applied to command event`() {
        // API Key 不得出现在事件流 — Sanitizer 脱敏
        Telemetry.recordCommand("self.config key sk-proj-abc123def456ghi789jkl012", true, 1, "MengPaw")
        val line = Telemetry.tailEvents(1).first()
        assertFalse("密钥不应出现在事件流", line.contains("sk-proj-abc123def456ghi789jkl012"))
        assertTrue("应被脱敏", line.contains("REDACTED"))
    }

    @Test
    fun `tail limit respected`() {
        repeat(30) { i -> Telemetry.recordCommand("self.status", true, i.toLong(), "MengPaw") }
        val tail = Telemetry.tailEvents(5)
        assertEquals("只取尾部 5 条", 5, tail.size)
        assertTrue("尾部为最后写入", tail.last().contains("\"ms\":29"))
    }

    @Test
    fun `empty stream returns empty list`() {
        assertTrue(Telemetry.tailEvents(10).isEmpty())
    }

    @Test
    fun `token and latency summary`() {
        Telemetry.recordLlm(100, 50, 200)
        Telemetry.recordLlm(200, 100, 400)

        val tokens = Telemetry.tokenSummary()
        assertTrue(tokens.contains("prompt=300"))
        assertTrue(tokens.contains("completion=150"))
        assertTrue(tokens.contains("total=450"))

        val latency = Telemetry.latencySummary()
        assertTrue("最近一次耗时", latency.contains("last=400ms"))
        assertTrue("平均耗时", latency.contains("avg=300ms"))
        assertTrue("请求数", latency.contains("2 requests"))
    }
}
