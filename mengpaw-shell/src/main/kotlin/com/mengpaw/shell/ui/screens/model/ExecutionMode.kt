// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens.model

/** 执行模式 — 用户通过 /命令 主动触发，非自动检测。与工作区 modes.md 模式菜单一一对应。
 *  v0.36: /Translate 已移除 — 翻译需求直接让 Agent 在普通对话/ReAct 中处理。 */
enum class ExecutionMode(val label: String, val prefix: String) {
    SWARM("Swarm", "/Swarm"),
    FLEET("Fleet", "/Fleet"),
    GOAL("Goal", "/Goal"),
    PLAN("Plan", "/Plan"),
    RESEARCH("Research", "/Research"),
    SILENT("Silent", "/Silent");
}
