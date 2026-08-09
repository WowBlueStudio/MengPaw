// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppRootSettingsItems.kt 的 shortToolSummary 回归测试 —
 * 全局工具/智能体工具副标题精简: 手机侧不被截断, 不展开也能懂大致用法。
 * 纯逻辑, 不触磁盘。
 */
class ShortToolSummaryTest {

    @Test
    fun 短描述原样返回() {
        assertEquals("拒绝框架配对请求", shortToolSummary("拒绝框架配对请求"))
    }

    @Test
    fun 剥离括号补充保留主句() {
        assertEquals("查看指定框架节点的详细信息",
            shortToolSummary("查看指定框架节点的详细信息 (名称/版本/Agent 列表)"))
        // 中文括号同样处理
        assertEquals("通过连接器插件连接外部框架节点",
            shortToolSummary("通过连接器插件连接外部框架节点（OpenClaw/QwenPaw 等）"))
    }

    @Test
    fun 分号切分取首段() {
        assertEquals("列出框架通讯录配对请求",
            shortToolSummary("列出框架通讯录配对请求 (待处理/已同意/已拒绝; 顺带清理 7 天前过期记录)"))
    }

    @Test
    fun 剥离插件名方括号前缀() {
        assertEquals("管理 OpenClaw 实例",
            shortToolSummary("[openclaw] 管理 OpenClaw 实例 (启停/状态/日志)"))
    }

    @Test
    fun 无标点长描述截断加省略号() {
        val s = shortToolSummary("这条描述没有任何标点符号分隔而且特别长需要在手机侧截断显示")
        assertTrue("截断后不得超上限", s.length <= 24)
        assertTrue("截断应有省略号", s.endsWith("…"))
    }

    @Test
    fun 空描述返回空() {
        assertEquals("", shortToolSummary(""))
        assertEquals("", shortToolSummary("   "))
    }
}
