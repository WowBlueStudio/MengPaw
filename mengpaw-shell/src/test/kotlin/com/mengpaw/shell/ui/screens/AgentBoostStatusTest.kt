// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.DataPaths
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Agent 引导进度面板数据层单测 (P2-13)。
 *
 * 覆盖 AgentBoostStatus.check 的 4 项判定:
 *  身份 = profile.md 已填名字 (模板 `- **名字：**` / AgentProfile `- 名称:` 两种格式);
 *  头像/主题/灵魂 = 对应文件存在 (theme.md 为全局 AGENTS/theme.md);
 *  boost.md 存在 = 引导流程仍在进行。
 */
class AgentBoostStatusTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        // 独立临时 BASE, 避免污染真机数据目录
        DataPaths.initialize(tmp.root.absolutePath)
    }

    private val agentDir: File get() = File(DataPaths.AGENTS, "MengPaw")

    private fun status() = AgentBoostStatus.check("MengPaw")

    @Test
    fun 空工作区全部未完成() {
        val s = status()
        assertFalse(s.identity); assertFalse(s.avatar); assertFalse(s.theme); assertFalse(s.soul)
        assertEquals(0, s.doneCount); assertFalse(s.allDone)
        assertFalse(s.boostExists)
    }

    @Test
    fun 模板手填名字视为身份完成() {
        agentDir.mkdirs()
        File(agentDir, "profile.md").writeText("- **名字：**小爪\n- **定位：**AI")
        assertTrue(status().identity)
    }

    @Test
    fun 模板未填名字视为身份未完成() {
        agentDir.mkdirs()
        File(agentDir, "profile.md").writeText("- **名字：**\n- **定位：**AI")
        assertFalse(status().identity)
    }

    @Test
    fun agentProfile格式名称行视为身份完成() {
        agentDir.mkdirs()
        File(agentDir, "profile.md").writeText("## 自身\n- 名称: 檬爪助手\n- 定位: 使魔")
        assertTrue(status().identity)
    }

    @Test
    fun profile缺失视为身份未完成() {
        assertFalse(status().identity)
    }

    @Test
    fun 四个文件齐全判定完成且boost存在时提示引导中() {
        agentDir.mkdirs()
        File(agentDir, "profile.md").writeText("- 名称: 小爪")
        File(agentDir, "avatar.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        File(DataPaths.AGENTS, "theme.md").writeText("dark")
        File(agentDir, "soul.md").writeText("灵魂")
        File(agentDir, "boost.md").writeText("引导中")

        val s = status()
        assertEquals(4, s.doneCount); assertTrue(s.allDone); assertTrue(s.boostExists)
        assertTrue(s.missingLabels("身份", "头像", "主题", "灵魂").isEmpty())
    }

    @Test
    fun 缺失项名称按固定顺序返回() {
        agentDir.mkdirs()
        File(agentDir, "profile.md").writeText("- 名称: 小爪")
        File(agentDir, "soul.md").writeText("灵魂")
        // 头像/主题缺失
        val missing = status().missingLabels("身份", "头像", "主题", "灵魂")
        assertEquals(listOf("头像", "主题"), missing)
    }
}
