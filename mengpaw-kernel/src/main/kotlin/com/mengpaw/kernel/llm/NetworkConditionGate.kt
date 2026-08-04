// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

/**
 * 网络状况门卫 (v0.29.2, 用户提议 — Android 系统网络状态 → 内核重试策略)。
 *
 * 由 shell 层注入 Android 实现 (ConnectivityManager 默认网络回调), kernel 保持
 * 零 Android 依赖 (SPI 注入模式, 先例: KernelLog.logger)。消费方: 仅
 * AdaptiveLlmProvider.executeWithRetry — 两条策略:
 *   - 断网 (isOnline=false) → 立即失败快返, 不烧重试次数 (重试必败且白耗配额/电量)
 *   - 弱网 (quality 0/1) → 退避时间放大 ×3/×1.5, 放慢节奏
 *
 * 信号强度说明: 真正的蜂窝 dBm 需要 READ_PHONE_STATE (危险权限, 需运行时弹窗)。
 * 本实现用免权限代理: NET_CAPABILITY_VALIDATED (互联网可达) + 下行带宽估算 —
 * 对"避免注定失败的重试"这一目的足够。
 */
interface NetworkConditionGate {
    /** 当前是否有可用默认网络 (onLost 即为 false)。@Volatile 线程安全。 */
    fun isOnline(): Boolean

    /**
     * 链路质量档位:
     * 0 = 差 (未 VALIDATED 或下行 <200kbps) — 退避 ×3
     * 1 = 中 (200kbps ~ 2Mbps)             — 退避 ×1.5
     * 2 = 好 (≥2Mbps)                       — 标准退避
     */
    fun quality(): Int
}
