// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens.model

import com.mengpaw.shell.ui.screens.PluginSuggestion

data class AgentTrace(
    val step: Int,
    val thought: String,
    val action: String?,
    val observation: String?
)

sealed class ChatMessageUi {
    abstract val stableId: String
    internal val createdAt: Long = java.lang.System.nanoTime()

    data class User(val content: String) : ChatMessageUi() {
        override val stableId get() = "u_$createdAt"
    }

    data class Agent(
        val content: String,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "a_$createdAt"
    }

    data class AgentWithTrace(
        val finalContent: String,
        val traces: List<AgentTrace>,
        val isRunning: Boolean = false,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "t_$createdAt"
    }

    data class System(val content: String) : ChatMessageUi() {
        override val stableId get() = "s_$createdAt"
    }

    data class Suggestion(val suggestion: PluginSuggestion) : ChatMessageUi() {
        override val stableId get() = "sg_$createdAt"
    }
}
