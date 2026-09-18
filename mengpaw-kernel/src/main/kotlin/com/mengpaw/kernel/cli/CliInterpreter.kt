// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

/**
 * Parses raw CLI command strings into structured commands.
 */
class CliInterpreter {

    /**
     * Parse a command string into a structured command.
     * Format: `namespace.command arg1 arg2 "arg with spaces" --flag value`
     */
    fun parse(input: String): ParsedCommand {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return ParsedCommand("", emptyList(), emptyMap())
        }

        val parts = tokenize(trimmed)
        if (parts.isEmpty()) {
            return ParsedCommand("", emptyList(), emptyMap())
        }

        val commandName = parts.first()
        val args = mutableListOf<String>()
        val flags = mutableMapOf<String, String>()

        var i = 1
        while (i < parts.size) {
            val part = parts[i]
            when {
                part.startsWith("--") -> {
                    val flagName = part.removePrefix("--")
                    if (i + 1 < parts.size && !parts[i + 1].startsWith("--")) {
                        flags[flagName] = parts[i + 1]
                        i += 2
                    } else {
                        flags[flagName] = "true"
                        i += 1
                    }
                }
                else -> {
                    // P0 修复 (Linux 原生选项): 单横线选项 (-n/-c/-i 等) 原样留在 args,
                    // 不再归入 flags。此前 -x 被当作"带值 flag"并吞掉下一个 token
                    // (grep -n 关键词 文件 → args=[文件] flags={n=关键词}), 且 Pipeline 还原
                    // flags 时只认双横线、一律加一个 '-' 并追加到所有位置参数之后, 结果
                    // 文件参数被推到选项之后、-n 变 --n —— grep/wc 的选项语义被静默破坏。
                    args.add(part)
                    i += 1
                }
            }
        }

        return ParsedCommand(commandName, args, flags)
    }

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var escape = false

        for (char in input) {
            when {
                escape -> {
                    current.append(char)
                    escape = false
                }
                char == '\\' -> escape = true
                char == '"' -> inQuote = !inQuote
                char.isWhitespace() && !inQuote -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }
}

/**
 * A parsed CLI command.
 */
data class ParsedCommand(
    val command: String,
    val args: List<String>,
    val flags: Map<String, String>
)
