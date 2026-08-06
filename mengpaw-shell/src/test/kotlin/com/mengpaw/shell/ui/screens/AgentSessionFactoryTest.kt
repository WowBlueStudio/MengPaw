// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentSessionFactory.kt 顶层常量 DEFAULT_AGENT_NAME 冒烟测试。
 *
 * 该常量是触发器任务/浏览器提炼/横幅切回/梦境整理等目标 agent 的唯一事实源
 * (P2 修复: 原 "MengPaw" 字面量散落多处) — 防误删/误改。
 */
class AgentSessionFactoryTest {

    @Test
    fun 默认主Agent名存在且非空() {
        assertTrue("DEFAULT_AGENT_NAME 不得为空", DEFAULT_AGENT_NAME.isNotBlank())
    }

    @Test
    fun 默认主Agent名与既定值一致() {
        // 变更需同步全链路引用 (AgentSessionFactory/AgentViewModel 等) — 测试作为哨兵
        assertEquals("MengPaw", DEFAULT_AGENT_NAME)
    }
}
