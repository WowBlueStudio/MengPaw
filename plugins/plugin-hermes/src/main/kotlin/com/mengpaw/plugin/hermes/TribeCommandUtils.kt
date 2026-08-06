// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

/**
 * Tribe 命令共用工具 — 从 TribePlugin 拆出 (delegate/kanban/cleanup 命令组共享的参数解析)。
 */
internal data class ParsedArgs(val positional: List<String>, val flags: Map<String, String>)

/** 解析 `--key value` 风格参数。 */
internal fun parseFlags(args: List<String>): ParsedArgs {
    val positional = mutableListOf<String>()
    val flags = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--") && args[i].length > 2) {
            val key = args[i].removePrefix("--")
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                flags[key] = args[i + 1]
                i += 2
            } else {
                flags[key] = "true"
                i += 1
            }
        } else {
            positional.add(args[i])
            i += 1
        }
    }
    return ParsedArgs(positional, flags)
}
