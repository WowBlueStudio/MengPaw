// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.skill

import com.mengpaw.kernel.DataPaths
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** PinnedSkills 用户指定技能清单 — 独立 tmp 目录隔离 (同 EvolutionStoreTest 模式)。 */
class PinnedSkillsTest {

    @Before
    fun initPaths() {
        val base = File(System.getProperty("java.io.tmpdir"), "mengpaw_pinned_test")
        base.deleteRecursively()
        DataPaths.initialize(base.absolutePath)
    }

    @Test
    fun `toggle adds and removes - list roundtrips`() {
        assertTrue("初始清单应为空", PinnedSkills.list().isEmpty())
        assertFalse("未指定技能不应命中", PinnedSkills.isPinned("tavily"))

        PinnedSkills.toggle("tavily")
        assertTrue("toggle 后应在清单中", PinnedSkills.isPinned("tavily"))
        assertEquals("清单应含 1 项", listOf("tavily"), PinnedSkills.list())

        PinnedSkills.toggle("tavily")
        assertFalse("再 toggle 应移除", PinnedSkills.isPinned("tavily"))
        assertTrue("清单应回到空", PinnedSkills.list().isEmpty())
    }

    @Test
    fun `persists across instances - file-backed`() {
        PinnedSkills.toggle("hermes")
        PinnedSkills.toggle("filesystem")
        // 重新读盘 (object 每次读文件, 等价于新实例)
        assertEquals("持久化应含两项", listOf("filesystem", "hermes").sorted(), PinnedSkills.list().sorted())
        File(DataPaths.SKILLS, ".pinned").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x01))
    }

    @Test
    fun `corrupted file degrades to empty list`() {
        File(DataPaths.SKILLS).mkdirs()
        // 路径被目录占据 — readLines 抛 IOException, 按空清单处理 (安全方向)
        // (InputStreamReader 对无效 UTF-8 默认 REPLACE 成 U+FFFD, 不抛异常, 故用目录形态)
        File(DataPaths.SKILLS, ".pinned").mkdirs()
        assertTrue("损坏文件按空清单处理 (安全方向)", PinnedSkills.list().isEmpty())
        assertFalse(PinnedSkills.isPinned("anything"))
    }

    @Test
    fun `remove only when pinned`() {
        PinnedSkills.toggle("tavily")
        PinnedSkills.remove("tavily")
        assertFalse("remove 应移除", PinnedSkills.isPinned("tavily"))
        PinnedSkills.remove("tavily")  // 重复移除不应抛异常
    }

    @Test
    fun `path traversal names rejected`() {
        assertFalse("穿越名应拒绝", PinnedSkills.toggle("../evil"))
        assertFalse("斜杠名应拒绝", PinnedSkills.toggle("a/b"))
    }
}
