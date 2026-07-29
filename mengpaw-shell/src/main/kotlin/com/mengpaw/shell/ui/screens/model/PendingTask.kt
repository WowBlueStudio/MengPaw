// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens.model

/** 待办任务 — Agent 运行时用户输入的排队任务。 */
data class PendingTask(
    val text: String,
    val maxSteps: Int = 50,
    val executionMode: ExecutionMode? = null,
    val agentRef: String? = null
)
