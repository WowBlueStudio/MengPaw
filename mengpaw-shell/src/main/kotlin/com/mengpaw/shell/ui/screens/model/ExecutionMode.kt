// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens.model

/** 执行模式 — 用户通过 /命令 主动触发，非自动检测。 */
enum class ExecutionMode(val label: String, val prefix: String) {
    MISSION("Mission", "/Mission"),
    GOAL("Goal", "/Goal"),
    PLAN("Plan", "/Plan"),
    RESEARCH("Research", "/Research"),
    TRANSLATE("Translate", "/Translate"),
    SILENT("Silent", "/Silent");
}
