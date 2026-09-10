// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * FrameworkAdapterRegistry 注册/查找与前缀别名 (2026-09-10)。
 *
 * 背景: TRAE IDE 连接器早期把 frameworkName 写成 `trea-ide` (字母转置, 上游产品名为 Trae),
 * 正名后 `find("trea-ide")` 仍须解析到 `trae-ide` 适配器 — 否则既存通讯录里的老 peer 连不上。
 */
class FrameworkAdapterRegistryTest {

    private class FakeAdapter(override val frameworkName: String, override val toolsDescription: String = "t") :
        FrameworkAdapter {
        override suspend fun connect(target: FrameworkTarget): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun callTool(tool: String, args: Map<String, String>): Result<String> = Result.success("")
        override fun isOnline(): Boolean = false
    }

    @Test
    fun `注册后按名查找_未注册返回null`() {
        val a = FakeAdapter("unit-test-fw")
        try {
            FrameworkAdapterRegistry.register(a)
            assertSame(a, FrameworkAdapterRegistry.find("unit-test-fw"))
            assertNull(FrameworkAdapterRegistry.find("unit-test-not-registered"))
        } finally {
            FrameworkAdapterRegistry.unregister("unit-test-fw")
        }
        assertNull("注销后不得再找到", FrameworkAdapterRegistry.find("unit-test-fw"))
    }

    @Test
    fun `历史拼写别名_trea-ide 解析到 trae-ide 适配器`() {
        val a = FakeAdapter("trae-ide")
        try {
            FrameworkAdapterRegistry.register(a)
            assertSame("正名应可查找", a, FrameworkAdapterRegistry.find("trae-ide"))
            assertSame("旧拼写应回退解析", a, FrameworkAdapterRegistry.find("trea-ide"))
            // list 只应含实际注册项 (别名不产生重复项)
            assertEquals(1, FrameworkAdapterRegistry.list().count { it.frameworkName == "trae-ide" })
        } finally {
            FrameworkAdapterRegistry.unregister("trae-ide")
        }
        assertNull(FrameworkAdapterRegistry.find("trea-ide"))
    }
}
