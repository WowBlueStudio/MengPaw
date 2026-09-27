// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BatteryOptimizationGuide.shouldPromptOnce] 单测 — "一次性引导"边界:
 * 只在"从未弹过 + 尚未白名单"时弹; 已弹过或已白名单一律不再打扰。
 * (Android 侧 Intent/AlertDialog 无法在 JVM 单测直跑, 故只锁纯判定。)
 */
class BatteryOptimizationGuideTest {

    @Test
    fun 首次未白名单时弹一次() {
        assertTrue(BatteryOptimizationGuide.shouldPromptOnce(markerExists = false, alreadyIgnoring = false))
    }

    @Test
    fun 已弹过不再弹() {
        assertFalse(BatteryOptimizationGuide.shouldPromptOnce(markerExists = true, alreadyIgnoring = false))
    }

    @Test
    fun 已在白名单不弹() {
        assertFalse(BatteryOptimizationGuide.shouldPromptOnce(markerExists = false, alreadyIgnoring = true))
    }

    @Test
    fun 已弹过且已白名单不弹() {
        assertFalse(BatteryOptimizationGuide.shouldPromptOnce(markerExists = true, alreadyIgnoring = true))
    }

    @Test
    fun 标记文件名固定() {
        assertTrue(BatteryOptimizationGuide.MARKER_FILE_NAME == "battery_opt_prompted")
    }
}
