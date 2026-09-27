// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.harness.CheckpointStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ResumePlanner] 判定表测试 (P0-3 / P1-6 接线点)。
 *
 * 断言意图: 锁得住的条件必须自动续跑, 锁不住/关掉开关/已有终态/判定为需清理时必须**不自动跑**
 * (宁可让用户手动确认, 也不能把不确定状态静默续下去)。全部用例用临时目录 + 假时钟, 无墙钟断言。
 */
class ResumePlannerTest {

    private val dir: File = createTempDir("resume_planner").apply { deleteOnExit() }

    /** 未创建的开关文件路径 → autoResumeEnabled() 视为开 (文件不存在 = 默认启用)。 */
    private fun enabledPlanner(lock: ResumeLock = ResumeLock(dir.absolutePath)) =
        ResumePlanner(lock, File(dir, "auto_resume_absent"))

    private fun disabledPlanner(lock: ResumeLock = ResumeLock(dir.absolutePath)): ResumePlanner {
        val flag = File(dir, "auto_resume_off")
        flag.writeText("false")
        flag.deleteOnExit()
        return ResumePlanner(lock, flag)
    }

    private fun runningCheckpoint(sessionId: String = "sess_a", step: Int = 3) = Checkpoint(
        sessionId = sessionId,
        step = step,
        remainingTask = "把仓库里所有 TODO 清掉",
        context = emptyMap(),
        status = CheckpointStatus.RUNNING,
        messages = listOf(
            Message("user", "把仓库里所有 TODO 清掉"),
            Message("assistant", "Command: grep TODO\nResult: 3 hits")
        )
    )

    @Test
    fun `RUNNING 且锁可得且开关开 判定自动续跑`() {
        val plan = enabledPlanner().plan(runningCheckpoint(), emptyList(), emptyList())

        assertTrue("应为 AutoResume, 实际 $plan", plan is ResumePlan.AutoResume)
        val auto = plan as ResumePlan.AutoResume
        assertEquals("sess_a", auto.sessionId)
        assertEquals(3, auto.step)
        assertEquals("把仓库里所有 TODO 清掉", auto.task)
        assertEquals("恢复用消息快照必须带上下文 (含工具 Observation)", 2, auto.messages.size)
    }

    @Test
    fun `COMPLETED 检查点不续跑 按新任务处理`() {
        val done = runningCheckpoint().copy(status = CheckpointStatus.COMPLETED, answer = "已完成")
        val plan = enabledPlanner().plan(done, emptyList(), emptyList())

        assertTrue("应为 Skip, 实际 $plan", plan is ResumePlan.Skip)
        assertEquals(ResumeSkipReason.ALREADY_TERMINAL, (plan as ResumePlan.Skip).reason)
    }

    @Test
    fun `FAILED 检查点同样不续跑`() {
        val failed = runningCheckpoint().copy(status = CheckpointStatus.FAILED, terminationReason = "loop_detected")
        val plan = enabledPlanner().plan(failed, emptyList(), emptyList())

        assertEquals(ResumeSkipReason.ALREADY_TERMINAL, (plan as ResumePlan.Skip).reason)
    }

    @Test
    fun `无检查点直接跳过`() {
        val plan = enabledPlanner().plan(null, emptyList(), emptyList())

        assertEquals(ResumeSkipReason.NO_CHECKPOINT, (plan as ResumePlan.Skip).reason)
    }

    @Test
    fun `锁被其他会话占用时改为提示用户`() {
        // 另一执行体先持锁 (未过期) → 本会话不得同时续跑
        val other = ResumeLock(dir.absolutePath)
        assertTrue(other.tryAcquire("sess_other"))

        val plan = enabledPlanner().plan(runningCheckpoint(), emptyList(), emptyList())

        assertTrue("应为 PromptUser, 实际 $plan", plan is ResumePlan.PromptUser)
        assertEquals(ResumeSkipReason.LOCK_UNAVAILABLE, (plan as ResumePlan.PromptUser).reason)
        // 被拒时不得留下半持有的锁状态
        assertFalse(other.isExpired())
    }

    @Test
    fun `漏锁过期后自动续跑可重新获得`() {
        var now = 1_000L
        val clock = { now }
        val stale = ResumeLock(dir.absolutePath, clock)
        assertTrue(stale.tryAcquire("sess_a"))
        now += ResumeLock.DEFAULT_TTL_MS + 1

        val plan = ResumePlanner(ResumeLock(dir.absolutePath, clock), File(dir, "absent")).plan(
            runningCheckpoint(), emptyList(), emptyList()
        )

        assertTrue("过期锁必须可抢 (否则用户被幽灵锁挡住), 实际 $plan", plan is ResumePlan.AutoResume)
    }

    @Test
    fun `auto_resume 关时改为提示用户`() {
        val plan = disabledPlanner().plan(runningCheckpoint(), emptyList(), emptyList())

        assertEquals(ResumeSkipReason.AUTO_RESUME_DISABLED, (plan as ResumePlan.PromptUser).reason)
        // 关掉开关时不得占用锁 (否则用户手动回复「继续」会被自己的判定挡住)
        assertTrue(ResumeLock(dir.absolutePath).tryAcquire("sess_a"))
    }

    @Test
    fun `连续错误达五次时判定需清理并改为提示用户`() {
        // decideRecovery: LLM_CALL_ERROR 且 payload.consecutive = true 累计 ≥5 → SuggestCleanup
        val events = (1..5).map {
            SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                sessionId = "sess_a",
                agentName = "MengPaw",
                summary = "boom $it",
                payload = mapOf("consecutive" to "true")
            )
        }
        val plan = enabledPlanner().plan(runningCheckpoint(), events, emptyList())

        assertTrue("应为 PromptUser, 实际 $plan", plan is ResumePlan.PromptUser)
        assertEquals(ResumeSkipReason.RECOVERY_DECLINED, (plan as ResumePlan.PromptUser).reason)
        assertTrue("提示文案需可读", plan.reason.userMessage.isNotBlank())
    }

    @Test
    fun `中断事件加待恢复记录仍可自动续跑`() {
        // P1-6 接线验证: decideRecovery 判 RecoverFromInterrupt 不阻断自动续跑
        val recovery = InterruptedTurnRecovery(
            pending = true,
            completedTools = listOf(InterruptedToolSummary("grep", listOf("a.md"), 2, 1))
        )
        val messages = listOf(Message("assistant", "…", localOnly = true, interruptedTurn = recovery))
        val events = listOf(
            SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_INTERRUPTED,
                sessionId = "sess_a", agentName = "MengPaw", summary = "interrupted"
            )
        )
        val plan = enabledPlanner().plan(runningCheckpoint(), events, messages)

        assertTrue("RecoverFromInterrupt 不应阻断自动续跑, 实际 $plan", plan is ResumePlan.AutoResume)
    }

    @Test
    fun `任务文本以 messages 末条 user 为准`() {
        val checkpoint = runningCheckpoint().copy(
            remainingTask = "旧任务快照",
            messages = listOf(
                Message("user", "旧任务快照"),
                Message("assistant", "Command: ls\nResult: ok"),
                Message("user", "继续。输出 Action: <命令> 和 Action Input: <参数>。")
            )
        )
        val plan = enabledPlanner().plan(checkpoint, emptyList(), emptyList()) as ResumePlan.AutoResume

        assertEquals("继续。输出 Action: <命令> 和 Action Input: <参数>。", plan.task)
    }

    @Test
    fun `messages 无 user 消息时回落 remainingTask`() {
        val checkpoint = runningCheckpoint().copy(
            remainingTask = "只剩助手消息",
            messages = listOf(Message("assistant", "Command: ls\nResult: ok"))
        )
        val plan = enabledPlanner().plan(checkpoint, emptyList(), emptyList()) as ResumePlan.AutoResume

        assertEquals("只剩助手消息", plan.task)
        assertNotNull(plan)
    }
}
