// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProcIdentity] 单测 — 平台安全 pid 读取。
 *
 * JVM 环境有 `ProcessHandle`, 正常路径可验; `/proc/self` 降级路径在 Windows 上返回 null,
 * 两者都只断言"不抛异常 + 形态合法", 不做平台分支硬断言 (同一套测试跑在 CI/Linux 也应绿)。
 */
class ProcIdentityTest {

    @Test
    fun `currentPid 不抛异常且形态合法`() {
        val pid = ProcIdentity.currentPid()
        assertTrue("pid 必须是正数 (-1 = 平台不可得, 仅允许在无 ProcessHandle 且无 /proc 时出现)", pid > 0)
        assertEquals("pid 应被缓存, 重复读取结果一致", pid, ProcIdentity.currentPid())
    }

    @Test
    fun `pidFromProcSelf 返回 null 或正数`() {
        val pid = ProcIdentity.pidFromProcSelf()
        assertTrue("解析结果只能是 null (无 /proc) 或合法 pid", pid == null || pid > 0)
    }

    @Test
    fun `JVM 环境应具备 ProcessHandle 能力`() {
        assertTrue("单测跑在 JVM, ProcessHandle 必然可反射解析", ProcIdentity.hasProcessHandle)
    }
}
