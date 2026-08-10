// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * Fleet 平台注入层 (v0.36 平台化) — fleet 命名空间命令常驻 kernel,
 * 平台相关能力 (通讯录/本机网络/身份/能力卡) 由各端注入:
 * Android 用 FrameworkPeerStore 实现, 桌面三端 (Win/OSX/Linux) 注入各自实现。
 * 任何跑 kernel 的端即具备指挥舰能力 (发起方即总指挥定案的前提)。
 */
data class FleetMember(
    val name: String,
    val fingerprint: String,
    val frameworkType: String,
    val address: String,
    val port: Int,
    val trusted: Boolean,
    val lastSeen: Long
)

object FleetPlatform {
    /** 舰队成员 (通讯录已信任 + 状态) — 平台注入。 */
    @Volatile var membersProvider: (() -> List<FleetMember>)? = null
    /** 本机局域网 IPv4 — 委派/扫描回调地址。 */
    @Volatile var localIpv4Provider: (() -> String?)? = null
    /** 本机 ACP 身份 (mengpaw-<短码>) — 消息 from。 */
    @Volatile var localPeerIdProvider: (() -> String)? = null
    /** 本机能力卡 JSON — 响应 fleet.scan 请求上报。 */
    @Volatile var capabilityProvider: (() -> String)? = null
}
