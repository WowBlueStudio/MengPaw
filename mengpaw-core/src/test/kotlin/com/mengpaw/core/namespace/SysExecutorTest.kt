// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * SysExecutor 命令表测试 — object 初始化不触达 Android API (命令映射为方法引用,
 * init 块仅向纯 JVM 的 CommandSearch 内存索引注册), 可直接读取 commands 表。
 *
 * 覆盖: 命令数量与文档一致 (84)、键唯一且命名规范、PERMISSION_MAP 引用的命令真实存在、
 * 中文同义词表非空无空词 (搜索索引质量冒烟)。
 */
class SysExecutorTest {

    /** 访问 commands 会触发 object 初始化 (含 CommandSearch 索引注册 — 纯 JVM 内存)。 */
    private val commands = SysExecutor.commands

    @Test
    fun 命令表非空且数量与文档一致() {
        assertEquals("命令数量与 SysExecutor 头注 84 条一致", 84, commands.size)
    }

    @Test
    fun 命令名全部唯一() {
        assertEquals("命令名不得重复", commands.size, commands.keys.toSet().size)
    }

    @Test
    fun 命令名命名规范_小写点分式() {
        commands.keys.forEach { name ->
            assertTrue("非法命令名: '$name'", COMMAND_NAME_PATTERN.matcher(name).matches())
        }
    }

    @Test
    fun 命令键无空白() {
        commands.keys.forEach { name ->
            assertFalse("命令名不得含空白: '$name'", name.any { it.isWhitespace() })
        }
    }

    @Test
    fun PERMISSION_MAP键都能对应到真实命令() {
        // PERMISSION_MAP 键带 "sys." 前缀 (UI 用全名), 命令表键去前缀后必须存在
        SysExecutor.PERMISSION_MAP.keys.forEach { fullName ->
            assertTrue(
                "PERMISSION_MAP 引用了不存在的命令: $fullName (命令表漂移!)",
                commands.containsKey(fullName.removePrefix("sys."))
            )
        }
    }

    @Test
    fun PERMISSION_MAP权限值均为合法权限常量() {
        SysExecutor.PERMISSION_MAP.values.forEach { perm ->
            assertTrue("非法权限值: $perm", perm.startsWith("android.permission."))
        }
    }

    @Test
    fun 中文同义词表覆盖所有命令且无空词() {
        val keywords = zhKeywords
        commands.keys.forEach { name ->
            assertTrue("命令 '$name' 缺少同义词条目 (搜索索引漂移)", keywords[name]?.isNotEmpty() == true)
            keywords[name]?.forEach { kw -> assertTrue("同义词不得为空: $name", kw.isNotBlank()) }
        }
    }

    companion object {
        private val COMMAND_NAME_PATTERN = Pattern.compile("[a-z0-9]+(\\.[a-z0-9]+)*")

        /** 同义词表自 v0.36.x 拆至独立文件 (守 400 行红线), 直接引用避免反射耦合。 */
        private val zhKeywords: Map<String, List<String>> =
            com.mengpaw.core.namespace.sys.SYS_KEYWORDS_ZH
    }
}
