// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens.model

/** 输入框标签 — 斜杠命令或 @mention 的活跃状态。 */
sealed class InputTag {
    abstract val label: String

    data class Mode(val mode: ExecutionMode) : InputTag() {
        override val label get() = mode.prefix
    }

    data class AgentRef(val agentName: String) : InputTag() {
        override val label get() = "@$agentName"
    }
}
