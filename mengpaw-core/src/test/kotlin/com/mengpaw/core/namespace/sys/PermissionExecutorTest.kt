// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PermissionExecutor 权限清单唯一源测试 (P2 修复: permissionList 遍历 PERMISSION_LABELS)。
 *
 * 说明: permissionList/permissionCheck 等方法需 SysExecutor.appContext (Android 上下文)
 * 与 Build.VERSION/checkSelf — 不可在 JVM 单测。此处用反射读取对象私有静态数据,
 * 验证"清单 - 可申请权限集合"三处数据不漂移 (防双源修复的回归保险):
 *   - PERMISSION_LABELS 非空、条目 label 齐全
 *   - DIALOG_PERMISSIONS ∪ SETTINGS_PERMISSIONS 的每个权限都在清单中有条目
 *     (可运行时申请的权限必须可被用户看到说明 — 曾漂移出 6 项)
 */
class PermissionExecutorTest {

    /** 反射读取 Kotlin object 私有字段 (private val → 对象私有字段, 静态或实例均可由 get(obj) 读出)。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> readPrivateField(name: String): T {
        val field = PermissionExecutor::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(PermissionExecutor) as T
    }

    private val labels: Map<String, String> by lazy { readPrivateField("PERMISSION_LABELS") }
    private val dialogPerms: Set<String> by lazy { readPrivateField("DIALOG_PERMISSIONS") }
    private val settingsPerms: Set<String> by lazy { readPrivateField("SETTINGS_PERMISSIONS") }

    @Test
    fun 权限清单非空() {
        assertFalse("权限清单不得为空", labels.isEmpty())
    }

    @Test
    fun 清单内每项都有非空label() {
        labels.forEach { (perm, label) ->
            assertTrue("权限 $perm 缺少 label", label.isNotBlank())
        }
    }

    @Test
    fun 清单键为合法权限名() {
        labels.keys.forEach { perm ->
            assertTrue("非法权限名: $perm", perm.startsWith("android.permission."))
        }
    }

    @Test
    fun 清单label无重复_防复制粘贴漂移() {
        assertEquals("label 重复说明有复制粘贴嫌疑", labels.size, labels.values.toSet().size)
    }

    @Test
    fun 所有可申请权限都有清单条目() {
        // 防双源漂移: DIALOG/SETTINGS 中列出的每个权限必须能在清单中找到说明 —
        // 否则 permissionList 输出缺项、permissionCheck 回退显示权限原名
        val union = dialogPerms + settingsPerms
        union.forEach { perm ->
            assertTrue("可申请权限 $perm 未列入 PERMISSION_LABELS (双源漂移!)", labels.containsKey(perm))
        }
    }

    @Test
    fun 清单条目数符合Manifest对齐规模() {
        // 19 项 = 原 14 + sys.* 敏感命令组 5 项 (SEND_SMS/READ_SMS/READ_CONTACTS/READ_CALL_LOG/CALL_PHONE)
        assertEquals(19, labels.size)
    }
}
