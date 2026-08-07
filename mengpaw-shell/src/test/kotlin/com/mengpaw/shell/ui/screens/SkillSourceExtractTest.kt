// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** extractSkillSource — 技能 frontmatter 来源标记解析 (设置页技能面板标签/删除判定数据源)。 */
class SkillSourceExtractTest {

    @Test
    fun `core and plugin sources extracted`() {
        val core = "---\nname: self\ndescription: 自我管理\nenabled: true\ncategory: system\nsource: core\n---\n# self"
        assertEquals("核心来源应解析", "core", extractSkillSource(core))
        val plugin = "---\nname: tavily\ndescription: AI 搜索\nenabled: true\ncategory: general\nsource: plugin\n---\n# tavily"
        assertEquals("插件来源应解析", "plugin", extractSkillSource(plugin))
    }

    @Test
    fun `user skill without source returns empty`() {
        val user = "---\nname: mine\ndescription: 用户技能\nenabled: true\ncategory: general\n---\n# mine"
        assertEquals("无 source 字段应为空", "", extractSkillSource(user))
        assertEquals("无 frontmatter 应为空", "", extractSkillSource("# 纯正文\n没有头"))
        assertEquals("空内容应为空", "", extractSkillSource(""))
    }
}
