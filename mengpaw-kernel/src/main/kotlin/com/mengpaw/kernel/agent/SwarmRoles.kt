// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * Fleet/火种角色模型路由的角色键 — 内核与壳层共用的单一事实源
 * （新增角色只需改此处 + providerFor 回退语义）。
 */
object SwarmRoles {
    const val PLANNER = "planner"
    const val WORKER = "worker"
    const val VERIFIER = "verifier"
    const val SYNTHESIZER = "synthesizer"
    const val WORKER_ALT = "worker.alt"

    /** 全部可配角色（顺序 = 壳层 UI 展示顺序）。 */
    val ALL: List<String> = listOf(PLANNER, WORKER, VERIFIER, SYNTHESIZER, WORKER_ALT)
}
