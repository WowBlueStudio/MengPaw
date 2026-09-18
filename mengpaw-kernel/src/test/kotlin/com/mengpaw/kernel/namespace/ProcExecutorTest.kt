// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * proc.* 命令测试 (v0.47.x) — 进程管理三件套 + 保留位边界。
 *
 * 覆盖: 命令面 (ps/info/kill 注册, exec/system 不注册) / 参数校验 /
 * kill 安全边界 (不许杀自己) / 不存在 pid 的如实报错。
 * 不依赖 Android: 用当前 JVM 进程自己的 pid 做正向样本。
 */
class ProcExecutorTest {

    private val ctx = ExecutionContext(sessionId = "test", agentName = "MengPaw")
    private val selfPid = ProcApi.currentPid()

    private fun run(cmd: String, args: List<String>) = runBlocking {
        ProcExecutor.commands.getValue(cmd).invoke(args, ctx)
    }

    // ── 命令面 ───────────────────────────────────────────────────────

    @Test
    fun `命令面只暴露进程管理三件套`() {
        assertEquals(setOf("ps", "info", "kill"), ProcExecutor.commands.keys)
        // 保留位不注册 — 语义是"能力永不开放", 由 SecurityPolicy.blockList 恒拒绝
        assertFalse("proc.exec 不得注册 (保留位)", "exec" in ProcExecutor.commands)
        assertFalse("proc.system 不得注册 (保留位)", "system" in ProcExecutor.commands)
    }

    // ── proc.ps ──────────────────────────────────────────────────────

    @Test
    fun `ps 返回进程列表或如实说明沙箱限制`() {
        val r = run("ps", emptyList())
        assertTrue("proc.ps 应成功返回: ${r.error}", r.success)
        assertTrue(
            "应给出进程表格或沙箱限制说明: ${r.output.take(120)}",
            r.output.contains("进程列表") || r.output.contains("无可枚举进程")
        )
    }

    @Test
    fun `ps 支持 limit 与 filter 参数`() {
        val limited = run("ps", listOf("--limit=3"))
        assertTrue(limited.success)
        val filtered = run("ps", listOf("--filter=${selfPid}"))
        assertTrue(filtered.success)
        if (filtered.output.contains("|")) {
            assertTrue("filter 应命中自己的 pid", filtered.output.contains(selfPid.toString()))
        }
    }

    // ── proc.info ────────────────────────────────────────────────────

    @Test
    fun `info 缺少 pid 报用法`() {
        val r = run("info", emptyList())
        assertFalse(r.success)
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, r.errorCode)
        assertTrue("错误须含用法引导: ${r.error}", r.error?.contains("proc.info <pid>") == true)
    }

    @Test
    fun `info 对不存在 pid 如实报错`() {
        // pid 用极大值 (远超 pid_max) — 保证不存在
        val r = run("info", listOf("999999999"))
        assertFalse(r.success)
        assertEquals(ErrorCodes.ERR_NOT_FOUND, r.errorCode)
    }

    @Test
    fun `info 可读自身进程详情`() {
        val r = run("info", listOf(selfPid.toString()))
        assertTrue("应能读取自身进程: ${r.error}", r.success)
        assertTrue("详情应含 pid", r.output.contains("pid=$selfPid"))
        assertTrue("详情应含存活字段", r.output.contains("存活"))
    }

    // ── proc.kill (安全边界) ─────────────────────────────────────────

    @Test
    fun `kill 缺少 pid 报用法`() {
        val r = run("kill", emptyList())
        assertFalse(r.success)
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, r.errorCode)
    }

    @Test
    fun `kill 拒绝终止自己`() {
        val r = run("kill", listOf(selfPid.toString()))
        assertFalse("必须拒绝杀自己 (否则当前会话必中断)", r.success)
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, r.errorCode)
        assertTrue("错误须说明原因: ${r.error}", r.error?.contains("拒绝终止自己") == true)
        assertTrue("当前进程应仍存活", ProcessHandle.current().isAlive)
    }

    @Test
    fun `kill 对不存在 pid 如实报错`() {
        val r = run("kill", listOf("999999999"))
        assertFalse(r.success)
        assertEquals(ErrorCodes.ERR_NOT_FOUND, r.errorCode)
        assertNotNull(r.error)
    }

    @Test
    fun `kill 非法 pid 文本按缺参处理`() {
        val r = run("kill", listOf("abc"))
        assertFalse(r.success)
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, r.errorCode)
    }
}
