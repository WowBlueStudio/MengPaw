// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复杂度自动检测测试 (P2 修复: 11-15 分最高复杂度原落 GOAL — 改为 MISSION)。
 *
 * detectComplexity/scoreComplexity 为纯 Kotlin (预编译正则 + 阈值判断), 可直接测试。
 * 评分规则: 风险词 +3 / 修改词 +2 / 域命中 ≤3 / 长度 >200 +2, >80 +1 / 序列词 +2 / 批量词 +2。
 *
 * 边界: ≤4 → REACT, 5-7 → GOAL, 8-10 → MISSION, ≥11 → MISSION (修复点)。
 */
class ComplexityDetectorTest {

    // ── 各档位边界值 ──

    @Test
    fun 简单问答0分_REACT() {
        assertEquals(0, scoreComplexity("你好"))
        assertEquals(LoopMode.REACT, detectComplexity("你好"))
    }

    @Test
    fun `4分边界_REACT`() {
        // 风险词(删除)+3 + 域(文件)+1 = 4 → REACT (≤4)
        assertEquals(4, scoreComplexity("删除文件"))
        assertEquals(LoopMode.REACT, detectComplexity("删除文件"))
    }

    @Test
    fun `5分边界_GOAL`() {
        // 修改词(创建)+2 + 域(文件)+1 + 序列词(然后)+2 = 5 → GOAL (5-7)
        assertEquals(5, scoreComplexity("创建文件然后"))
        assertEquals(LoopMode.GOAL, detectComplexity("创建文件然后"))
    }

    @Test
    fun `7分边界_GOAL`() {
        // 风险(删除)+3 + 域(文件,浏览器)+2 + 序列(然后)+2 = 7 → GOAL
        assertEquals(7, scoreComplexity("删除文件浏览器然后"))
        assertEquals(LoopMode.GOAL, detectComplexity("删除文件浏览器然后"))
    }

    @Test
    fun `8分边界_MISSION`() {
        // 风险+3 + 域(文件)+1 + 序列(然后)+2 + 批量(所有)+2 = 8 → MISSION (8-10)
        assertEquals(8, scoreComplexity("删除文件然后所有"))
        assertEquals(LoopMode.MISSION, detectComplexity("删除文件然后所有"))
    }

    @Test
    fun `10分边界_MISSION`() {
        // 风险+3 + 域(文件,浏览器,系统)+3 + 序列+2 + 批量+2 = 10 → MISSION
        assertEquals(10, scoreComplexity("删除文件然后所有浏览器系统"))
        assertEquals(LoopMode.MISSION, detectComplexity("删除文件然后所有浏览器系统"))
    }

    // ── P2 修复: 11-12 分最高复杂度 → MISSION (修复前落入 GOAL) ──

    @Test
    fun `11分最高复杂度_MISSION_修复回归`() {
        // 风险+3 + 域 9 类命中封顶+3 + 序列(然后)+2 + 批量(所有)+2 = 10;
        // 补足长度 85 (>80) +1 → 11 — 修复前 11-15 分落 GOAL, 现必须 MISSION
        val task = "删除文件网络插件记忆系统浏览器搜索翻译应用然后所有" + "认真".repeat(30)
        assertTrue("长度 85 应落在 >80 档", task.length in 81..200)
        assertEquals(11, scoreComplexity(task))
        assertEquals(
            "11 分属最高复杂度档, 必须为 MISSION (P2 修复回归)",
            LoopMode.MISSION, detectComplexity(task)
        )
    }

    @Test
    fun `12分上限_MISSION`() {
        // 风险+3 + 域 9 类命中封顶+3 + 序列+2 + 批量+2 = 10; 长度 >200 +2 → 12 (理论上限)
        val longTask = "删除 文件 网络 插件 记忆 系统 浏览器 搜索 翻译 应用 然后 所有 " +
            "任务需要认真执行耐心完成每一步骤".repeat(12)
        assertTrue(longTask.length > 200)
        assertEquals(12, scoreComplexity(longTask))
        assertEquals(LoopMode.MISSION, detectComplexity(longTask))
    }

    // ── 评分维度 ──

    @Test
    fun 风险词优先于修改词_不叠加() {
        // 同时含"删除"(风险)与"创建"(修改) — else-if 只计风险 +3
        assertEquals(3, scoreComplexity("删除并创建"))
    }

    @Test
    fun 域命中封顶3分() {
        // 9 类域全命中 → 3 分封顶
        assertEquals(3, scoreComplexity("文件 网络 插件 记忆 系统 浏览器 搜索 翻译 应用"))
    }

    @Test
    fun 长任务加分() {
        // 81 字符 (>80) +1
        val mid = "a".repeat(81)
        assertEquals(1, scoreComplexity(mid))
        // 201 字符 (>200) +2
        val long = "b".repeat(201)
        assertEquals(2, scoreComplexity(long))
    }

    @Test
    fun 评分非负() {
        listOf("", "问个好", "123", "删除 创建 安装 所有 然后").forEach { t ->
            assertTrue("评分不得为负: '$t'", scoreComplexity(t) >= 0)
        }
    }
}
