// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 多模式进度断点续跑回归 (工作流 C) — GOAL/Ralph 进度档与 SWARM 看板档的落盘语义。
 *
 * 覆盖:
 * - ① 落盘 → 新实例读同一目录 → 全部进度/预算字段完整 (GOAL 与 Ralph 各一组);
 * - ② 旧档 (缺新字段的 JSON) 可解码且不丢既有字段;
 * - ③ 预算恢复语义: 剩余预算 = 总额 − 已消耗 (不是总额);
 * - ⑤ 落盘损坏容错 (坏 JSON → 视为无进度, 不抛异常)。
 *
 * 全程零墙钟阈值断言; 每个用例独立临时目录 (DataPaths.initialize 定向)。
 */
class MultiModeResumeStoreTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "mengpaw_multimode_${UUID.randomUUID()}")

    @Before
    fun setUp() {
        base.mkdirs()
        DataPaths.initialize(base.absolutePath)
    }

    @After
    fun cleanup() {
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    // ── ① GOAL 进度档往返 ────────────────────────────────────────────

    @Test
    fun `goal progress survives new instance load`() {
        GoalSessionStore.saveProgress(
            GoalSession.goalProgress(
                goal = "生成季度报告", agentName = "小檬", iteration = 7, maxIterations = 20,
                tokensUsed = 42_000, maxTokens = 300_000,
                lastVerdict = "NEEDS_REVISION", lastFeedback = "还差财务数据"
            )
        )
        // 模拟进程重启: 不依赖任何内存态, 只按 agentName 重新读盘
        val loaded = GoalSessionStore.loadForResume("小檬")
        assertNotNull("进度档应可读回", loaded)
        assertEquals("goal 应完整", "生成季度报告", loaded?.goal)
        assertEquals("iteration 应完整", 7, loaded?.iteration)
        assertEquals("maxIterations 应完整", 20, loaded?.maxIterations)
        assertEquals("tokensUsed 应完整", 42_000, loaded?.tokensUsed)
        assertEquals("token 上限应完整", 300_000, loaded?.maxTokens)
        assertEquals("lastVerdict 应完整", "NEEDS_REVISION", loaded?.lastVerdict)
        assertEquals("lastFeedback 应完整", "还差财务数据", loaded?.lastFeedback)
        assertEquals("mode 应归位 GOAL", GoalSession.MODE_GOAL, loaded?.mode)
        assertEquals("agentName 应完整", "小檬", loaded?.agentName)
    }

    // ── ① Ralph 轮次与预算档往返 ─────────────────────────────────────

    @Test
    fun `ralph round and budget survive new instance load`() = withRalphProgress { session ->
        // Ralph 的进度档走同一套 GoalSessionStore, 文件按 mode 分离
        assertFalse(
            "Ralph 与 GOAL 必须是不同文件 (不得互相覆盖)",
            GoalSessionStore.progressFile("小檬", GoalSession.MODE_RALPH) ==
                GoalSessionStore.progressFile("小檬", GoalSession.MODE_GOAL)
        )
        assertTrue(
            "Ralph 档应在同一进度目录下",
            GoalSessionStore.progressFile("小檬", GoalSession.MODE_RALPH)
                .startsWith(GoalSessionStore.progressDir("小檬"))
        )
        assertNull("Ralph 档不应污染 GOAL 读取", GoalSessionStore.loadForResume("小檬"))
        assertEquals("已完成轮次应完整", 3, session.round)
        assertEquals("累计 tokens 应完整", 900L, session.tokensConsumed)
        assertEquals("看板已消耗步数应完整", 25, session.iteration)
        assertEquals("看板总预算应完整", 60, session.maxIterations)
        assertEquals("mode 应为 ralph", GoalSession.MODE_RALPH, session.mode)
        assertEquals("交接文本应完整", "上一轮交接", session.lastFeedback)
    }

    /** 写入一份 Ralph 进度档并读回 (新实例语义)。 */
    private fun withRalphProgress(block: (GoalSession) -> Unit) {
        GoalSessionStore.saveProgress(
            GoalSession(
                goal = "写目标报告", active = true, iteration = 25, maxIterations = 60,
                maxTokens = 0, tokensUsed = 900, lastVerdict = "", lastFeedback = "上一轮交接",
                mode = GoalSession.MODE_RALPH, agentName = "小檬",
                round = 3, tokensConsumed = 900L
            )
        )
        val loaded = GoalSessionStore.load(
            File(GoalSessionStore.progressFile("小檬", GoalSession.MODE_RALPH))
        )
        assertNotNull("Ralph 档应可读回", loaded)
        block(loaded ?: return)
    }

    // ── ③ 预算恢复语义: 剩余 = 总额 − 已消耗 ─────────────────────────

    @Test
    fun `resume keeps budget continuous instead of resetting`() {
        val progress = GoalSession.goalProgress(
            goal = "长任务", agentName = "小檬", iteration = 12, maxIterations = 20,
            tokensUsed = 120_000, maxTokens = 300_000,
            lastVerdict = "NEEDS_REVISION", lastFeedback = ""
        )
        assertEquals("剩余预算 = 总额 − 已消耗", 180_000, progress.tokenBudgetRemaining)
        assertEquals("剩余轮次 = 上限 − 已完成", 8, progress.iterationsRemaining)

        GoalSessionStore.saveProgress(progress)
        val loaded = GoalSessionStore.loadForResume("小檬")
        assertEquals("重启后剩余预算不得回到总额", 180_000, loaded?.tokenBudgetRemaining)
        assertEquals("重启后剩余轮次不得回到上限", 8, loaded?.iterationsRemaining)
    }

    @Test
    fun `swarm progress keeps consumed steps continuous`() {
        val saved = swarmProgress(consumed = 14, total = 40)
        SwarmProgressStore.saveProgress("小檬", saved)
        val loaded = SwarmProgressStore.loadForResume("小檬")
        assertNotNull("看板档应可读回", loaded)
        assertEquals("已消耗步数应完整", 14, loaded?.consumedSteps)
        assertEquals("剩余步数 = 总额 − 已消耗", 26, loaded?.stepsRemaining)
        // 预算恢复: 用持久化值预热计数器, 剩余不是总额
        val restored = SwarmBudget.restore(loaded?.totalSteps ?: 0, loaded?.consumedSteps ?: 0)
        assertEquals("恢复后剩余步数应连续", 26, restored.remaining)
        assertFalse("已消耗 14 步不应判定为耗尽", restored.exhausted)
    }

    @Test
    fun `swarm budget restore clamps out of range values`() {
        assertEquals("超上限应钳制", 0, SwarmBudget.restore(10, 99).remaining)
        assertEquals("负值应钳制为 0", 10, SwarmBudget.restore(10, -5).remaining)
    }

    // ── ② 旧档兼容 (缺新字段) ────────────────────────────────────────

    @Test
    fun `legacy goal archive without new fields still decodes`() {
        // 改造前的 JSON 形态: 没有 mode/agentName/round/tokensConsumed/updatedAt
        val legacy = """{"goal":"旧任务","active":true,"iteration":4,"maxIterations":20,""" +
            """"maxTokens":300000,"tokensUsed":5000,"lastVerdict":"NO","lastFeedback":"继续"}"""
        val file = File(GoalSessionStore.progressFile("小檬", GoalSession.MODE_GOAL))
        file.parentFile?.mkdirs()
        try { file.writeText(legacy) } catch (_: Exception) {}

        val loaded = GoalSessionStore.loadForResume("小檬")
        assertNotNull("旧档必须仍可解码", loaded)
        assertEquals("既有字段不得丢失 (goal)", "旧任务", loaded?.goal)
        assertEquals("既有字段不得丢失 (iteration)", 4, loaded?.iteration)
        assertEquals("既有字段不得丢失 (tokensUsed)", 5_000, loaded?.tokensUsed)
        assertEquals("既有字段不得丢失 (maxIterations)", 20, loaded?.maxIterations)
        assertEquals("既有字段不得丢失 (lastVerdict)", "NO", loaded?.lastVerdict)
        assertEquals("既有字段不得丢失 (lastFeedback)", "继续", loaded?.lastFeedback)
        // 新增字段取默认值, 不炸
        assertEquals("缺 mode 应回退 GOAL", GoalSession.MODE_GOAL, loaded?.mode)
        assertEquals("缺 agentName 应为空", "", loaded?.agentName)
        assertEquals("缺 round 应为 0", 0, loaded?.round)
        assertEquals("缺 tokensConsumed 应为 0", 0L, loaded?.tokensConsumed)
    }

    @Test
    fun `legacy swarm archive without new fields still decodes`() {
        val legacy = """{"task":"旧火种任务","totalSteps":40,"consumedSteps":9}"""
        val file = File(SwarmProgressStore.progressFile("小檬"))
        file.parentFile?.mkdirs()
        try { file.writeText(legacy) } catch (_: Exception) {}

        val loaded = SwarmProgressStore.loadForResume("小檬")
        assertNotNull("旧看板档必须仍可解码", loaded)
        assertEquals("既有字段不得丢失 (task)", "旧火种任务", loaded?.task)
        assertEquals("既有字段不得丢失 (consumedSteps)", 9, loaded?.consumedSteps)
        assertEquals("既有字段不得丢失 (totalSteps)", 40, loaded?.totalSteps)
        assertEquals("缺 subtasks 应为空列表", emptyList<SwarmProgress.SubtaskState>(), loaded?.subtasks)
        assertEquals("缺 updatedAt 应为 0", 0L, loaded?.updatedAt)
    }

    // ── ⑤ 损坏容错 ──────────────────────────────────────────────────

    @Test
    fun `corrupt archives are treated as no progress`() {
        val goalFile = File(GoalSessionStore.progressFile("小檬", GoalSession.MODE_GOAL))
        val swarmFile = File(SwarmProgressStore.progressFile("小檬"))
        goalFile.parentFile?.mkdirs()
        try {
            goalFile.writeText("{ 这不是 JSON")
            swarmFile.writeText("[]")
        } catch (_: Exception) {}

        assertNull("坏 GOAL 档应视为无进度 (不抛异常)", GoalSessionStore.loadForResume("小檬"))
        assertNull("坏看板档应视为无进度 (不抛异常)", SwarmProgressStore.loadForResume("小檬"))
        assertNull("空 agentName 也不得抛异常", GoalSessionStore.loadForResume(""))
        assertNull("不存在文件返回 null", SwarmProgressStore.loadForResume("不存在的Agent"))
    }

    @Test
    fun `empty archive file is treated as no progress`() {
        val file = File(SwarmProgressStore.progressFile("小檬"))
        file.parentFile?.mkdirs()
        try { file.writeText("") } catch (_: Exception) {}
        assertNull("空文件应视为无进度", SwarmProgressStore.loadForResume("小檬"))
    }

    // ── 落盘格式与路径 ──────────────────────────────────────────────

    @Test
    fun `progress files live under config mode progress dir`() {
        val goalPath = GoalSessionStore.progressFile("小檬", GoalSession.MODE_GOAL)
        assertTrue("GOAL 档应落在 模式进度/ 目录下", goalPath.contains("模式进度"))
        assertTrue("GOAL 档应为 goal.json", goalPath.endsWith("goal.json"))
        assertTrue("看板档应为 swarm.json", SwarmProgressStore.progressFile("小檬").endsWith("swarm.json"))
        assertTrue("Ralph 档应为 ralph.json",
            GoalSessionStore.progressFile("小檬", GoalSession.MODE_RALPH).endsWith("ralph.json"))
    }

    @Test
    fun `agent name is sanitized against path traversal`() {
        GoalSessionStore.saveProgress(
            GoalSession.goalProgress(
                goal = "穿越测试", agentName = "../../逃逸", iteration = 1, maxIterations = 20,
                tokensUsed = 0, maxTokens = 300_000, lastVerdict = "", lastFeedback = ""
            )
        )
        val files = File(GoalSessionStore.progressRoot).walkTopDown().filter { it.isFile }.toList()
        assertEquals("只应产生一个进度文件", 1, files.size)
        assertTrue("文件必须留在进度根目录内", files[0].absolutePath.startsWith(base.absolutePath))
    }

    private fun swarmProgress(consumed: Int, total: Int) = SwarmProgress(
        task = "调研任务",
        totalSteps = total,
        consumedSteps = consumed,
        subtasks = listOf(
            SwarmProgress.SubtaskState("a", "子任务A", "worker", SwarmProgress.VERIFIED, "A 已完成", 2, 0, "PASS"),
            SwarmProgress.SubtaskState("b", "子任务B", "worker", SwarmProgress.RUNNING, "", 1, 0, "")
        ),
        updatedAt = 1_700_000_000_000L
    )
}
