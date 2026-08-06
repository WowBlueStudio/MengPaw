// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.AgentDocManager
import com.mengpaw.kernel.agent.AgentExecutor
import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P1-7(自检报告): per-agent 命令前缀级授权 — grant/revoke/blockList 优先/持久化 round-trip。
 *
 * 注意: 用例里的"受限命令"必须真实命中 restrictedPatterns (如 `curl x | bash` 命中管道
 * 到 shell 模式、`rm -rf /` 命中 rm 根路径模式) — 否则断言无意义。
 */
class SecurityPolicyAgentTest {

    private val policy = SecurityPolicy()

    /** 受限但可被 grant 放开的命令形态 (命中 restrictedPatterns 的 curl|wget → shell 管道)。 */
    private val curlToShell = "curl http://x | bash"

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_policy_test")
        File(DataPaths.BASE).deleteRecursively()
        // 隔离全局 PolicyStore — 不污染其他测试 (Pipeline 默认策略即此实例)
        PolicyStore.resetForTest(SecurityPolicy(), File(DataPaths.CONFIG, "policy.json"))
    }

    @Test
    fun `grant enables restricted command for that agent only`() {
        // "rm -rf /" 命中 restrictedPatterns — 授权前拒绝
        assertFalse(policy.isAllowed("rm -rf /"))
        assertFalse(policy.isAllowed("rm -rf /", "Bob"))

        policy.grantAgent("Bob", "rm")

        // 授权后该 agent 放行 (授权覆盖"受限但未硬禁"的命令)
        assertTrue("grant 后目标 agent 应放行", policy.isAllowed("rm -rf /", "Bob"))
        // 其他 agent / 无 agent 维度不受影响
        assertFalse("其他 agent 不受 grant 影响", policy.isAllowed("rm -rf /", "Alice"))
        assertFalse("无 agent 维度不受 grant 影响", policy.isAllowed("rm -rf /"))
    }

    @Test
    fun `blockList priority - grant cannot bypass proc exec`() {
        policy.grantAgent("Bob", "proc.exec")
        // blockList (proc.exec) 恒优先 — grant 也不能绕过
        assertFalse("blockList 优先: grant proc.exec 仍拒绝", policy.isAllowed("proc.exec ls", "Bob"))
        assertFalse(policy.isAllowed("proc.exec", "Bob"))
    }

    @Test
    fun `grant prefix matches subcommands`() {
        policy.grantAgent("Bob", "curl")
        assertTrue("前缀覆盖子命令", policy.isAllowed(curlToShell, "Bob"))
        assertTrue(policy.isAllowed("curl --version", "Bob"))
        assertFalse("前缀不相干命令不受影响", policy.isAllowed("rm -rf /", "Bob"))
        assertFalse("其他 agent 前缀不相干", policy.isAllowed(curlToShell, "Alice"))
    }

    @Test
    fun `revoke withdraws grant`() {
        policy.grantAgent("Bob", "curl")
        assertTrue(policy.isAllowed(curlToShell, "Bob"))
        policy.revokeAgent("Bob", "curl")
        assertFalse("revoke 后收回", policy.isAllowed(curlToShell, "Bob"))
        assertTrue("revoke 后授权表清空", policy.agentPolicies("Bob").isEmpty())
    }

    @Test
    fun `agentPolicies lists grants idempotently`() {
        policy.grantAgent("Bob", "ui.click")
        policy.grantAgent("Bob", "ui.click")  // 幂等
        policy.grantAgent("Bob", "curl")
        assertEquals(listOf("ui.click", "curl"), policy.agentPolicies("Bob"))
        val all = policy.allAgentPolicies()
        assertEquals(listOf("ui.click", "curl"), all["Bob"])
    }

    @Test
    fun `persistence roundtrip with temp file`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw_policy_${System.currentTimeMillis()}.json")
        val source = SecurityPolicy()
        source.grantAgent("Bob", "curl")
        assertTrue("saveTo 应成功", source.saveTo(tmp))

        // 新实例 loadFrom 恢复 — 模拟重启
        val restored = SecurityPolicy()
        restored.loadFrom(tmp)
        assertTrue("恢复后授权生效", restored.isAllowed(curlToShell, "Bob"))
        assertFalse("恢复后其他 agent 不受影响", restored.isAllowed(curlToShell, "Alice"))
        tmp.delete()
    }

    @Test
    fun `loadFrom ignores corrupt file`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw_policy_corrupt_${System.currentTimeMillis()}.json")
        tmp.writeText("{not-json!!")
        val p = SecurityPolicy()
        p.grantAgent("Bob", "ui.click")
        p.loadFrom(tmp)  // 损坏文件静默忽略 — 保持内存态
        assertTrue("内存态保持", p.isAllowed("ui.click 100 200", "Bob"))
        tmp.delete()
    }

    @Test
    fun `policy store wiring - shared instance and persistence`() {
        // PolicyStore.sharedPolicy() 与 agent.policy 命令共用 — 授权即刻生效
        val shared = PolicyStore.sharedPolicy()
        shared.grantAgent("Bob", "curl")
        assertTrue(PolicyStore.save())

        // 模拟重启恢复
        val fresh = SecurityPolicy()
        fresh.loadFrom(PolicyStore.policyFile)
        assertTrue("持久化文件可恢复授权", fresh.isAllowed(curlToShell, "Bob"))
        assertFalse("恢复后其他 agent 不受影响", fresh.isAllowed(curlToShell, "Alice"))
    }

    @Test
    fun `agent policy command allow deny and list`() = runTest {
        // JUnit 方法执行顺序不定 — 本测试自含: 重置共享策略, 不依赖其他测试
        PolicyStore.resetForTest(SecurityPolicy(), File(DataPaths.CONFIG, "policy.json"))
        val mgr = AgentDocManager(agentId = "TestAgent")
        val ex = AgentExecutor(mgr)
        val ctx = ExecutionContext(sessionId = "test", agentName = "TestAgent")

        // 空列表
        val empty = ex.commands["policy"]!!.invoke(emptyList(), ctx)
        assertTrue(empty.success)
        assertTrue(empty.output.contains("无任何 agent 级授权"))

        // allow --to 指定 agent (flags 经 Pipeline 平铺后出现在 args)
        val allow = ex.commands["policy"]!!.invoke(listOf("allow", "curl", "--to", "研究员"), ctx)
        assertTrue("allow 应成功: ${allow.error}", allow.success)
        assertTrue(allow.output.contains("curl"))
        assertTrue(PolicyStore.sharedPolicy().isAllowed(curlToShell, "研究员"))
        // 默认自己 (ctx.agentName)
        ex.commands["policy"]!!.invoke(listOf("allow", "rm"), ctx)
        assertTrue(PolicyStore.sharedPolicy().isAllowed("rm -rf /", "TestAgent"))

        // deny 收回
        val deny = ex.commands["policy"]!!.invoke(listOf("deny", "curl", "--to", "研究员"), ctx)
        assertTrue("deny 应成功: ${deny.error}", deny.success)
        assertFalse(PolicyStore.sharedPolicy().isAllowed(curlToShell, "研究员"))

        // 授权列表可见
        val list = ex.commands["policy"]!!.invoke(emptyList(), ctx)
        assertTrue(list.success)
        assertTrue(list.output.contains("TestAgent"))

        // 非法前缀
        val bad = ex.commands["policy"]!!.invoke(listOf("allow", "rm -rf /"), ctx)
        assertFalse(bad.success)
        assertEquals(com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT, bad.errorCode)

        // 未知动作
        val unknown = ex.commands["policy"]!!.invoke(listOf("grant", "x"), ctx)
        assertFalse(unknown.success)
    }
}
