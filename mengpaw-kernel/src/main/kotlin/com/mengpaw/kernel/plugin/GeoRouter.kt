// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

/**
 * Smart geo-router for plugin downloads.
 *
 * Detects China vs. rest-of-world via system locale/timezone — instant, no network.
 *   China (CN) → Gitee primary, GitHub fallback
 *   Other       → GitHub primary, Gitee fallback
 */
object GeoRouter {
    /** Returns true if the device is likely in mainland China. */
    fun isChina(): Boolean {
        // 系统语言检测 — 简体中文
        val lang = java.util.Locale.getDefault().language
        val country = java.util.Locale.getDefault().country
        if (lang == "zh" && (country.isBlank() || country == "CN")) return true

        // 时区检测 — 中国标准时间
        val tz = java.util.TimeZone.getDefault().id
        if (tz == "Asia/Shanghai" || tz == "Asia/Chongqing" ||
            tz == "Asia/Harbin" || tz == "Asia/Urumqi") return true

        return false
    }
}
