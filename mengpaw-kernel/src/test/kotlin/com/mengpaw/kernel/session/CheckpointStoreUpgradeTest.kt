// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.harness.CheckpointStatus
import com.mengpaw.harness.jvm.JvmHarnessFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 检查点升级测试 — 工作流 D: 三态模型 / 前缀歧义 / 旧格式兼容 / 损坏容错 /
 * 截断策略 / 观测指标。
 *
 * 每个用例自带独立临时目录 (含 System.nanoTime 后缀), 互不干扰;
 * 不依赖执行顺序, 也不做任何墙钟阈值断言 (可重复、可在慢 CI 上稳定通过)。
 */
class CheckpointStoreUpgradeTest {

    /** 单条 content 落盘上限 — 与实现约定一致: 8000 字符。 */
    private val maxContentChars = 8000

    @Test
    fun `新格式往返完整保留状态消息与答复`() = runBlocking {
        val dir = tempDir("ckpt_roundtrip")
        val manager = CheckpointManager(dir.absolutePath)
        val messages = listOf(
            Message(role = "user", content = "统计 src 下有多少 Kotlin 文件"),
            Message(role = "assistant", content = "Thought: 先列目录", reasoning = "需要先看目录结构"),
            Message(role = "system", content = "Observation: 42 个文件\n含换行")
        )
        val saved = Checkpoint(
            sessionId = "roundtrip",
            step = 7,
            remainingTask = "汇总结果",
            context = mapOf("agent" to "MengPaw"),
            status = CheckpointStatus.COMPLETED,
            messages = messages,
            updatedAt = 1_700_000_000_777L,
            terminationReason = "final_answer",
            answer = "共 42 个"
        )

        manager.save(saved)
        val loaded = manager.loadLatest("roundtrip")
        assertNotNull("写入后应能读回", loaded)
        val actual = loaded ?: return@runBlocking

        assertEquals("roundtrip", actual.sessionId)
        assertEquals(7, actual.step)
        assertEquals("汇总结果", actual.remainingTask)
        assertEquals("MengPaw", actual.context["agent"])
        assertEquals(CheckpointStatus.COMPLETED, actual.status)
        assertEquals(1_700_000_000_777L, actual.updatedAt)
        assertEquals("final_answer", actual.terminationReason)
        assertEquals("共 42 个", actual.answer)
        assertEquals(3, actual.messages.size)
        assertEquals("需要先看目录结构", actual.messages[1].reasoning)
        assertTrue("中文与换行必须原样保留", actual.messages[2].content.contains("含换行"))
    }

    @Test
    fun `前缀会话互不污染`() = runBlocking {
        val dir = tempDir("ckpt_prefix")
        val manager = CheckpointManager(dir.absolutePath)

        // 修复前: loadLatest("a") 用 startsWith 匹配, 会读到 "ab" 的档
        manager.save(Checkpoint("a", 1, "任务 A", emptyMap(), updatedAt = 1_000L))
        manager.save(Checkpoint("ab", 2, "任务 B", emptyMap(), updatedAt = 2_000L))

        assertEquals("任务 A", manager.loadLatest("a")?.remainingTask)
        assertEquals("任务 B", manager.loadLatest("ab")?.remainingTask)
        assertEquals(1, manager.loadLatest("a")?.step)
        assertEquals(2, manager.loadLatest("ab")?.step)
        assertNull("不存在的会话必须读不到 (前缀不再误命中)", manager.loadLatest("abc"))

        // 精确列出: "a" 与 "ab" 是两个会话, 不得互相吞并
        assertEquals(listOf("a", "ab"), manager.listSessions())
    }

    @Test
    fun `旧格式档案仍可读且按默认值补齐新字段`() = runBlocking {
        val dir = tempDir("ckpt_legacy")
        val manager = CheckpointManager(dir.absolutePath)
        manager.save(Checkpoint("legacy_pin", 9, "新格式进度", emptyMap(), updatedAt = 9_000L))

        // 旧格式: 文件名 {sessionId}_step_{n}.json, JSON 只有升级前的 5 个字段
        val legacyJson = """
            {
              "sessionId": "legacy_only",
              "step": 1,
              "remainingTask": "旧格式进度",
              "context": {"k": "v"},
              "createdAt": 1000
            }
        """.trimIndent()
        JvmHarnessFileSystem.writeText(
            path = "${dir.absolutePath}/legacy_pin_step_1.json",
            content = legacyJson.replace("legacy_only", "legacy_pin"),
            createParentDirs = true
        )

        // 同会话两份档: 新格式 updatedAt 更新 → 应优先返回它 (旧档不得盖新)
        assertEquals("新格式进度", manager.loadLatest("legacy_pin")?.remainingTask)

        // 只留旧档时, 缺失字段按默认值补齐 (status = RUNNING, updatedAt 回落 createdAt)
        val legacyOnlyDir = tempDir("ckpt_legacy_only")
        val legacyManager = CheckpointManager(legacyOnlyDir.absolutePath)
        JvmHarnessFileSystem.writeText(
            path = "${legacyOnlyDir.absolutePath}/legacy_only_step_3.json",
            content = legacyJson,
            createParentDirs = true
        )
        val legacy = legacyManager.loadLatest("legacy_only")
        assertNotNull("旧格式档必须仍可读 (向后兼容)", legacy)
        assertEquals(1, legacy?.step)
        assertEquals("旧格式进度", legacy?.remainingTask)
        assertEquals("v", legacy?.context?.get("k"))
        assertEquals(CheckpointStatus.RUNNING, legacy?.status)
        assertTrue("旧档无 messages → 默认空列表", legacy?.messages?.isEmpty() == true)
        assertEquals(1000L, legacy?.updatedAt)
        assertNull("旧档无 terminationReason → 默认 null", legacy?.terminationReason)
    }

    @Test
    fun `损坏档视为无检查点且不抛异常`(): Unit = runBlocking {
        val dir = tempDir("ckpt_corrupt")
        val manager = CheckpointManager(dir.absolutePath)
        JvmHarnessFileSystem.writeText(
            path = "${dir.absolutePath}/broken__step_3.json",
            content = "{ 这不是合法 JSON",
            createParentDirs = true
        )

        assertNull("损坏档应视为无检查点, 不抛异常", manager.loadLatest("broken"))
        assertNull("同步路径同理", manager.loadLatestSync("broken"))
        // 文件名可解析 → 会话仍可枚举 (供宿主巡检), 内容不可用也不该让收尾流程炸掉
        assertEquals(listOf("broken"), manager.listSessions())
        manager.cleanup("broken")
        manager.clear("broken")
    }

    @Test
    fun `超长消息被截断且条数保留最近`() = runBlocking {
        val dir = tempDir("ckpt_truncate")
        val manager = CheckpointManager(dir.absolutePath)

        val longContent = "x".repeat(maxContentChars + 500)
        manager.save(Checkpoint("truncate", 1, "截断", emptyMap(), messages = listOf(Message("user", longContent))))
        val loaded = manager.loadLatest("truncate")
        assertNotNull(loaded)
        assertEquals("单条 content 必须截断到上限", maxContentChars, loaded?.messages?.first()?.content?.length)

        // 250 条 → 只留最近 200 条, 且保留的是尾部 (最近) 而非头部
        val many = (1..250).map { Message(role = "user", content = "m$it") }
        manager.save(Checkpoint("truncate_many", 1, "条数", emptyMap(), messages = many))
        val capped = manager.loadLatest("truncate_many")
        assertNotNull(capped)
        assertEquals("条数上限生效", 200, capped?.messages?.size)
        assertEquals("m51", capped?.messages?.first()?.content)
        assertEquals("m250", capped?.messages?.last()?.content)
    }

    @Test
    fun `指标单调递增且失败计数可观测`() = runBlocking {
        CheckpointMetrics.reset()
        val dir = tempDir("ckpt_metrics")
        val manager = CheckpointManager(dir.absolutePath)

        manager.save(Checkpoint("m1", 1, "指标", emptyMap(), messages = listOf(Message("user", "hi"))))
        manager.save(Checkpoint("m1", 2, "指标", emptyMap(), messages = listOf(Message("user", "hi"))))
        manager.loadLatest("m1")
        manager.loadLatest("missing_session")

        // 用 checkNotNull 绑定取值: 失败信息更直观, 也避开可空类型与 Long 重载的歧义
        val snapshot = CheckpointMetrics.snapshot()
        assertEquals(2L, checkNotNull(snapshot["save_count"]))
        assertEquals(0L, checkNotNull(snapshot["save_fail_count"]))
        // 一次 loadLatest("m1") = 一次命中; loadLatest("missing_session") = 一次未命中。
        // (原断言写 2L 是把两次调用都当成命中 —— 与实现无关的笔误)
        assertEquals(1L, checkNotNull(snapshot["load_hit"]))
        assertEquals(1L, checkNotNull(snapshot["load_miss"]))
        assertEquals(0L, checkNotNull(snapshot["load_fail"]))
        assertTrue("落盘耗时应被累加 (>=0)", checkNotNull(snapshot["save_millis_total"]) >= 0L)
        assertEquals("最后一次落盘只含 1 条消息", 1L, checkNotNull(snapshot["last_message_count"]))
        assertTrue("落盘字节数应 > 0", checkNotNull(snapshot["last_bytes"]) > 0L)

        // 失败路径: storageDir 指向一个已存在的文件 → writeText 必失败, 但不得抛给调用方
        val blocked = tempDir("ckpt_metrics_block")
        JvmHarnessFileSystem.writeText("${blocked.absolutePath}/blocker", "not a dir")
        CheckpointManager("${blocked.absolutePath}/blocker").save(Checkpoint("m2", 1, "必失败", emptyMap()))

        val afterFail = CheckpointMetrics.snapshot()
        assertEquals("失败必须计入 save_fail_count", 1L, checkNotNull(afterFail["save_fail_count"]))
        assertEquals("成功计数不应被失败污染", 2L, checkNotNull(afterFail["save_count"]))

        CheckpointMetrics.reset()
        assertTrue("reset 后应全部归零", CheckpointMetrics.snapshot().values.all { it == 0L })
    }

    private fun tempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), prefix + "_" + System.nanoTime())
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }
}
