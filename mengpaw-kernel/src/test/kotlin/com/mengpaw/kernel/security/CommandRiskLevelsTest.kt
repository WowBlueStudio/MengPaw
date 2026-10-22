// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `twin.*` 命令风险分级 (P2 顺手补 P0) — 此前 17 条命令全部未登记,
 * 未登记虽然默认 LOW, 但"解绑/设备丢失"这类破坏信任关系的操作必须显式 MID,
 * 否则默认放行且无法被审计口径区分。
 */
class CommandRiskLevelsTest {

    private val twinCommands = listOf(
        "twin.start", "twin.stop", "twin.status",
        "twin.peers", "twin.peer.info", "twin.peer.add",
        "twin.pair", "twin.unpair",
        "twin.sync", "twin.sync.auto", "twin.sync.qos",
        "twin.capabilities", "twin.model",
        "twin.delegate", "twin.route",
        "twin.lost", "twin.recover"
    )

    @Test
    fun `17 条 twin 命令全部登记且数量一致`() {
        assertEquals("插件注册的命令数必须与登记数一致", 17, twinCommands.size)
        val missing = twinCommands.filter { it !in CommandRiskLevels.LEVELS }
        assertTrue("未登记风险级的 twin 命令: $missing", missing.isEmpty())
    }

    @Test
    fun `twin lost 与 twin unpair 不再是 LOW`() {
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("twin.lost"))
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("twin.unpair"))
        assertNotEquals("设备丢失广播必须是中危", RiskLevel.LOW, CommandRiskLevels.levelOf("twin.lost"))
    }

    @Test
    fun `其余 twin 命令为普通级 且带参数时判定不漂移`() {
        val low = twinCommands - setOf("twin.lost", "twin.unpair")
        low.forEach { name ->
            assertEquals("$name 应为普通级", RiskLevel.LOW, CommandRiskLevels.levelOf(name))
        }
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("twin.lost peer-abc"))
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("twin.sync peer-abc"))
    }
}
