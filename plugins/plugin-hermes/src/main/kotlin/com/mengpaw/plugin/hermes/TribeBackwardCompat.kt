// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * Tribe 向后兼容层 — 将 hermes.* 命令路由到 tribe.* 实现并显示弃用提示。
 *
 * 每个函数包装一个对应的 tribe.* 实现，在成功结果前附加弃用提示。
 */
object TribeBackwardCompat {

    private const val DEPRECATION_PREFIX = "⚠️ `hermes.*` 已弃用，请使用 `tribe.*` 替代。\n\n"

    /** 为成功结果加上弃用前缀。 */
    private fun prependDeprecation(result: ExecutionResult): ExecutionResult {
        return if (result.success) {
            result.copy(output = DEPRECATION_PREFIX + result.output)
        } else result
    }

    suspend fun team(args: List<String>, ctx: ExecutionContext, tribeImpl: suspend (List<String>, ExecutionContext) -> ExecutionResult): ExecutionResult =
        prependDeprecation(tribeImpl(args, ctx))

    suspend fun discover(args: List<String>, ctx: ExecutionContext, tribeImpl: suspend (List<String>, ExecutionContext) -> ExecutionResult): ExecutionResult =
        prependDeprecation(tribeImpl(args, ctx))

    suspend fun delegate(args: List<String>, ctx: ExecutionContext, tribeImpl: suspend (List<String>, ExecutionContext) -> ExecutionResult): ExecutionResult {
        val augmentedArgs = if (args.size >= 2 && !args.contains("--priority")) {
            args + listOf("--priority", "P1")
        } else args
        return prependDeprecation(tribeImpl(augmentedArgs, ctx))
    }

    suspend fun ask(args: List<String>, ctx: ExecutionContext, tribeImpl: suspend (List<String>, ExecutionContext) -> ExecutionResult): ExecutionResult =
        prependDeprecation(tribeImpl(args, ctx))

    suspend fun memo(args: List<String>, ctx: ExecutionContext, tribeImpl: suspend (List<String>, ExecutionContext) -> ExecutionResult): ExecutionResult =
        prependDeprecation(tribeImpl(args, ctx))

    suspend fun role(args: List<String>, ctx: ExecutionContext, tribeImpl: suspend (List<String>, ExecutionContext) -> ExecutionResult): ExecutionResult =
        prependDeprecation(tribeImpl(args, ctx))
}
