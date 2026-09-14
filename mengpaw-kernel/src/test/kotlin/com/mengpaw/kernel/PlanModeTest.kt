// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.*
import com.mengpaw.kernel.llm.*
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlanModeTest {

    private val mockLlm = MockLlmProvider()

    private val sessionManager = SessionManager()

    private val engine = AgentEngine(
        llmProvider = mockLlm,
        sessionManager = sessionManager
    )

    @Test
    fun `plan step default status is pending`() {
        val step = PlanStep(0, "Check file", "fs.cat /test.txt", "File contents shown")
        assertEquals(PlanStepStatus.PENDING, step.status)
        assertEquals(0, step.index)
        assertEquals("Check file", step.description)
        assertEquals("fs.cat /test.txt", step.action)
        assertEquals("File contents shown", step.expectedOutcome)
    }

    @Test
    fun `task plan counts completed steps`() {
        val steps = listOf(
            PlanStep(0, "Step 1", "cmd1", "outcome1", PlanStepStatus.COMPLETED),
            PlanStep(1, "Step 2", "cmd2", "outcome2", PlanStepStatus.PENDING),
            PlanStep(2, "Step 3", "cmd3", "outcome3", PlanStepStatus.COMPLETED)
        )
        val plan = TaskPlan("Test task", steps)
        assertEquals(3, plan.totalSteps)
        assertEquals(2, plan.completedSteps)
        assertFalse(plan.isComplete)
    }

    @Test
    fun `task plan is complete when all steps done`() {
        val steps = listOf(
            PlanStep(0, "Step 1", "cmd1", "outcome1", PlanStepStatus.COMPLETED),
            PlanStep(1, "Step 2", "cmd2", "outcome2", PlanStepStatus.COMPLETED)
        )
        val plan = TaskPlan("Test task", steps)
        assertTrue(plan.isComplete)
    }

    @Test
    fun `empty task plan`() {
        val plan = TaskPlan("Empty task", emptyList())
        assertEquals(0, plan.totalSteps)
        assertEquals(0, plan.completedSteps)
        assertTrue(plan.isComplete)
    }

    // ── Plan Parsing Tests ───────────────────────────────────────────────

    @Test
    fun `generatePlan parses LLM response into TaskPlan`() = runBlocking {
        mockLlm.nextResponse = """
            STEP 1: Check current directory contents | ACTION: fs.ls /data | EXPECT: List of files and directories
            STEP 2: Read configuration file | ACTION: fs.cat /data/config.json | EXPECT: Configuration content displayed
            STEP 3: Verify system status | ACTION: self.status | EXPECT: System health report
        """.trimIndent()

        val plan = engine.generatePlan("Analyze system state")
        assertEquals("Analyze system state", plan.task)
        assertEquals(3, plan.totalSteps)
        assertEquals("Check current directory contents", plan.steps[0].description)
        assertEquals("fs.ls /data", plan.steps[0].action)
        assertEquals("List of files and directories", plan.steps[0].expectedOutcome)
        assertEquals("Read configuration file", plan.steps[1].description)
        assertEquals("Verify system status", plan.steps[2].description)
    }

    @Test
    fun `generatePlan handles single step`() = runBlocking {
        mockLlm.nextResponse = """
            STEP 1: Just check the status | ACTION: self.status | EXPECT: Status OK
        """.trimIndent()

        val plan = engine.generatePlan("Check status")
        assertEquals(1, plan.totalSteps)
        assertEquals("Just check the status", plan.steps[0].description)
    }

    @Test
    fun `generatePlan handles empty response gracefully`() = runBlocking {
        mockLlm.nextResponse = "No plan available"

        val plan = engine.generatePlan("Do something")
        assertEquals(0, plan.totalSteps)
    }

    @Test
    fun `generatePlan extracts steps with minimal whitespace`() = runBlocking {
        mockLlm.nextResponse = "STEP 1: A|ACTION:cmd|EXPECT:ok"

        val plan = engine.generatePlan("Minimal")
        assertEquals(1, plan.totalSteps)
        assertEquals("A", plan.steps[0].description)
        assertEquals("cmd", plan.steps[0].action)
        assertEquals("ok", plan.steps[0].expectedOutcome)
    }

    @Test
    fun `generatePlan ignores non-step lines`() = runBlocking {
        mockLlm.nextResponse = """
            Here is a plan for your task:
            STEP 1: First thing | ACTION: fs.ls | EXPECT: Directory listing
            Some extra commentary
            STEP 2: Second thing | ACTION: self.status | EXPECT: Status report
            END
        """.trimIndent()

        val plan = engine.generatePlan("Task")
        assertEquals(2, plan.totalSteps)
    }

    // ── AgentState Tests ─────────────────────────────────────────────────

}
