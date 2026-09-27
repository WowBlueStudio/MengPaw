// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.harness.CheckpointStatus
import com.mengpaw.kernel.DataPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 「重启 → 续跑」契约链集成测试 — **不经过 AgentEngine / LLM**, 只串联内核的纯组件。
 *
 * 为什么要单独一条链 (而不是继续堆单元测试): [CheckpointManager] / [ToolIntentLog] /
 * [ResumeLock] / [ResumePlanner] / [CheckpointMetrics] 各自都有单测, 但"进程死了再起来,
 * 这套设施是否还接得上"是**跨组件的契约** — 任何一处改了键、改了读法或改了默认值,
 * 单测全绿而链路仍可能断。本类按生产恢复入口的调用顺序把它们串起来跑一遍:
 *
 * ① [CheckpointManager] 写 RUNNING (带工具 Observation) → **新实例**读回 (模拟进程重启);
 * ② [ToolIntentLog] 只 `begin` 不 `finish` → [ResumePlanner] 把未完成工具名带进
 *    [ResumePlan.AutoResume.pendingIntents] (供上层注入"先核对再决定"提示);
 * ③ [ResumeLock] 单会话锁: 同会话重入幂等 / 异会话被拒 / 释放后可再取;
 * ④ 终态检查点 → [ResumePlanner] 返回 [ResumePlan.Skip] ([ResumeSkipReason.ALREADY_TERMINAL]);
 * ⑤ 整条链跑完 [CheckpointMetrics] 的 `save_count` / `load_hit` 确实递增 (先 `reset()`)。
 *
 * **可测性守恒**: 全部断言都是**状态与计数**, 无墙钟阈值; 每个用例一个
 * `Files.createTempDirectory` 临时目录 + `finally` 清理, 且用 [DataPaths.initialize] 把
 * 全局路径指向该目录 — [ResumePlanner] 与 [ToolIntentLog] 的默认落点都由它派生
 * (`{BASE}/配置`、`{BASE}/会话检查点/intents`), 不初始化就会写到真实设备路径上。
 */
class ResumeChainIntegrationTest {

    private companion object {
        /** 续跑会话 id (含下划线, 顺带覆盖文件名分隔符与 id 内下划线的歧义场景)。 */
        const val SESSION_ID = "sess_resume_chain"

        /** 未确认完成的工具名 — 必须能原样出现在 [ResumePlan.AutoResume.pendingIntents]。 */
        const val PENDING_TOOL = "deploy.check"

        /** 工具观察结果文本 (模拟真实 Observation 落进 messages)。 */
        const val OBSERVATION = "Observation: deploy.check 目标 release 存在, 版本 0.48.1"
    }

    /**
     * 建临时目录 + 把 [DataPaths] 指向它, 跑完无论成败都删除目录。
     *
     * lambda 标注 `suspend`: 链路上 `CheckpointManager.save` / `ToolIntentLog.begin` 都是挂起函数,
     * 非挂起 lambda 里调不了 (首版即因此 6 处编译失败)。
     */
    private suspend fun withTempBase(prefix: String, body: suspend (base: String) -> Unit) {
        val base = Files.createTempDirectory(prefix).toFile()
        try {
            DataPaths.initialize(base.absolutePath)
            body(base.absolutePath)
        } finally {
            base.deleteRecursively()
        }
    }

    /** 一条 RUNNING 检查点: 用户任务 + 助手动作 + 工具观察 (Observation 是"不丢上下文"的关键)。 */
    private fun runningCheckpoint(sessionId: String, step: Int): Checkpoint = Checkpoint(
        sessionId = sessionId,
        step = step,
        remainingTask = "部署 release 并核对版本",
        context = mapOf("agentName" to "MengPaw"),
        status = CheckpointStatus.RUNNING,
        messages = listOf(
            Message("user", "部署 release 并核对版本"),
            Message("assistant", "Action: $PENDING_TOOL"),
            Message("user", OBSERVATION)
        )
    )

    // ── ① 重启后读回完整进度 (新实例 = 进程重启) ───────────────────────────

    @Test
    fun `链1 检查点跨实例读回状态步号与消息快照`() = runBlocking {
        withTempBase("mengpaw-resume-chain1") { base ->
            val dir = "$base/会话检查点"
            // 写入侧实例 (相当于崩溃前的旧进程)
            CheckpointManager(dir).save(runningCheckpoint(SESSION_ID, step = 3))
            // 读取侧**全新实例** — 模拟重启后内存状态全丢, 只剩磁盘
            val reloaded = CheckpointManager(dir).loadLatestSync(SESSION_ID)

            assertNotNull("新实例必须能读回检查点 (重启后唯一的续跑锚点)", reloaded)
            assertEquals("步号必须原样保留 (续跑从这里接续)", 3, reloaded?.step)
            assertEquals(
                "状态必须仍是 RUNNING — 只有 RUNNING 才可续跑",
                CheckpointStatus.RUNNING, reloaded?.status
            )
            assertEquals("消息快照条数必须完整", 3, reloaded?.messages?.size)
            assertEquals(
                "工具 Observation 必须还在消息里 (否则续跑丢上下文)",
                OBSERVATION, reloaded?.messages?.last()?.content
            )
            assertEquals(
                "会话归属以档内 sessionId 为权威",
                SESSION_ID, reloaded?.sessionId
            )
        }
    }

    // ── ② 未完成工具意图进入续跑计划 ──────────────────────────────────────

    @Test
    fun `链2 未完成意图经计划器进入待核对清单`() = runBlocking {
        withTempBase("mengpaw-resume-chain2") { base ->
            val dir = "$base/会话检查点"
            CheckpointManager(dir).save(runningCheckpoint(SESSION_ID, step = 3))
            // 只 begin 不 finish = 工具已声明执行但结果未知 (崩溃窗口的危险态)
            val args = """{"target":"release"}"""
            val intentLog = ToolIntentLog()
            intentLog.begin(
                ToolIntent(
                    sessionId = SESSION_ID,
                    step = 3,
                    toolCallId = ToolIntentLog.toolCallIdOf(
                        SESSION_ID, 3, PENDING_TOOL, ToolIntentLog.digestOf(args)
                    ),
                    toolName = PENDING_TOOL,
                    argsDigest = ToolIntentLog.digestOf(args),
                    state = IntentState.PENDING,
                    startedAt = 1_000L
                )
            )
            val pending = intentLog.pending(SESSION_ID)
            assertEquals("未 finish 的意图必须可见", 1, pending.size)
            assertEquals("PENDING 态必须如实落盘", IntentState.PENDING, pending.first().state)

            planOf(dir, SESSION_ID, CheckpointStatus.RUNNING, step = 3)
            val plan = typedPlan<ResumePlan.AutoResume>()

            assertEquals("续跑必须沿用检查点原会话 id", SESSION_ID, plan.sessionId)
            assertEquals("续跑步号必须从检查点接续", 3, plan.step)
            assertEquals("快照消息必须随计划传给续跑方", 3, plan.messages.size)
            assertTrue(
                "未完成工具名必须进入待核对清单 (只提示不重放), 实际: ${plan.pendingIntents}",
                plan.pendingIntents.contains(PENDING_TOOL)
            )
        }
    }

    // ── ③ 单会话锁: 同会话幂等 / 异会话拒绝 / 释放后可再取 ─────────────────

    @Test
    fun `链3 单会话锁同会话幂等异会话被拒释放后可再取`() = runBlocking {
        withTempBase("mengpaw-resume-chain3") { base ->
            val dir = "$base/检查点锁"
            val now = 1_000_000L
            val lock = ResumeLock(dir, clock = { now })

            assertTrue("首次获取必须成功", lock.tryAcquire(SESSION_ID))
            assertEquals("持有者必须记录为本次会话", SESSION_ID, lock.heldSessionId)
            assertTrue(
                "同一会话重复获取必须幂等放行 (进程内重复触发同一续跑入口)",
                lock.tryAcquire(SESSION_ID)
            )

            val otherSession = "sess_other"
            assertFalse(
                "未过期锁被他人持有时, 异会话不得抢到",
                lock.tryAcquire(otherSession)
            )
            assertEquals(
                "拒绝原因必须是「他会话持有」",
                ResumeLock.DeniedReason.HELD_BY_OTHER_SESSION, lock.lastDeniedReason
            )

            assertTrue("释放自己的锁必须成功", lock.release())
            assertNull("释放后不得再有持有者", lock.heldSessionId)
            assertTrue("释放后必须可再次获取", lock.tryAcquire(SESSION_ID))
            assertTrue("清理: 释放第二次获取的锁", lock.release())
        }
    }

    // ── ④ 终态检查点不可续跑 ─────────────────────────────────────────────

    @Test
    fun `链4 终态检查点判定为无需续跑`() = runBlocking {
        withTempBase("mengpaw-resume-chain4") { base ->
            val dir = "$base/会话检查点"
            CheckpointManager(dir).save(
                runningCheckpoint(SESSION_ID, step = 4).copy(
                    status = CheckpointStatus.COMPLETED,
                    terminationReason = null,
                    answer = "已完成"
                )
            )

            planOf(dir, SESSION_ID, CheckpointStatus.COMPLETED, step = 4)
            val plan = typedPlan<ResumePlan.Skip>()

            assertEquals(
                "跳过原因必须是「已有终态结论」",
                ResumeSkipReason.ALREADY_TERMINAL, plan.reason
            )
        }
    }

    // ── ⑤ 指标在同一流程里递增 ───────────────────────────────────────────

    @Test
    fun `链5 指标记录落盘成功与恢复命中`() = runBlocking {
        withTempBase("mengpaw-resume-chain5") { base ->
            val dir = "$base/会话检查点"
            CheckpointMetrics.reset()
            val before = CheckpointMetrics.snapshot()

            CheckpointManager(dir).save(runningCheckpoint(SESSION_ID, step = 1))
            CheckpointManager(dir).save(runningCheckpoint(SESSION_ID, step = 2))
            CheckpointManager(dir).loadLatestSync(SESSION_ID)

            val after = CheckpointMetrics.snapshot()
            assertEquals(
                "两次成功落盘 → save_count 必须 +2",
                (before["save_count"] ?: -1L) + 2L, after["save_count"]
            )
            assertEquals(
                "一次成功恢复 → load_hit 必须 +1",
                (before["load_hit"] ?: -1L) + 1L, after["load_hit"]
            )
            assertEquals(
                "落盘成功不构成失败计数",
                before["save_fail_count"], after["save_fail_count"]
            )
            assertTrue(
                "落盘字节数必须被观测到 (证明埋点走的是真实落盘路径)",
                (after["last_bytes"] ?: 0L) > 0L
            )
            CheckpointMetrics.reset()
        }
    }

    // ── 共用: 按真实恢复入口的顺序取事实后交给 ResumePlanner ──────────────

    /**
     * 复刻 [com.mengpaw.kernel.AgentRuntime.planResume] 的取事实顺序 (检查点 → 事件 → 消息),
     * 但事件固定为空列表: 空事件走 [decideRecovery] 的兼容回落分支, 在"无待恢复中断"时返回
     * [RecoveryDecision.NoAction], 因而不阻断自动续跑 — 这正是"冷启动后直接续跑"的常见形态。
     *
     * 为什么不让调用方自己 `assertTrue(plan is X)` + `as X`: 计划结果要先经 `Any?` 形参判断,
     * 之后再对同一个值做强转既冗长又容易被改坏 (类型判据与转换分家)。这里把值存进字段,
     * 读取时类型即 [ResumePlan], 再用 [typedPlan] 的 reified 检查给出**带类型名的失败信息**,
     * 断言与取值合成一步。
     *
     * 锁单会话 (目录 = 检查点目录) 保证计划器内部的 `tryAcquire` 独立于用例 ③ 的锁目录,
     * 两个用例互不干扰; 时间基准用固定假时钟, 不做墙钟断言。
     */
    private fun planOf(dir: String, sessionId: String, status: CheckpointStatus, step: Int): ResumePlan {
        val checkpoint = CheckpointManager(dir).loadLatestSync(sessionId)
        assertNotNull("取事实阶段必须能读到检查点 (会话 $sessionId)", checkpoint)
        assertEquals("读到的检查点状态必须与写入一致", status, checkpoint?.status)
        assertEquals("读到的检查点步号必须与写入一致", step, checkpoint?.step)
        val planner = ResumePlanner(ResumeLock("$dir/锁", clock = { 1_000_000L }))
        lastPlan = planner.plan(
            checkpoint = checkpoint,
            recentEvents = emptyList(),
            messages = checkpoint?.messages ?: emptyList()
        )
        return lastPlan
    }

    /** 取出 [planOf] 刚算出的计划并按 [T] 断言类型 (失败信息含类型不匹配, 不做无声转换)。 */
    private inline fun <reified T : ResumePlan> typedPlan(): T {
        val plan = lastPlan
        if (plan !is T) {
            throw AssertionError("恢复计划类型不符: 期望 ${T::class.simpleName}, 实际 $plan")
        }
        return plan
    }

    /** 最近一次 [planOf] 的结果 — 见其 KDoc 的智能转换说明。 */
    private var lastPlan: ResumePlan = ResumePlan.Skip(ResumeSkipReason.NO_CHECKPOINT)
}
