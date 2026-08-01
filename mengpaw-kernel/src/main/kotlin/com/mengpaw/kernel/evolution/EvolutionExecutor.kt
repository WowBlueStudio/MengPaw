// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * 进化系统命令入口 — evolution.* 命名空间(内核注册, 始终可用)。
 *
 * 薄分派: 所有命令委托给当前生效的 [EvolutionProvider] ([EvolutionProviderRegistry.active]).
 * 默认实现 = [EvolutionEngine]; 第三方插件可实现 EvolutionProvider 覆盖
 * (plugin-evolution 为内置默认实现, 与梦境模式 plugin-dream 同模式)。
 */
object EvolutionExecutor {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "audit" to { args, ctx -> dispatch("audit", args, ctx) },
        "report" to { args, ctx -> dispatch("report", args, ctx) },
        "learn.command" to { args, ctx -> dispatch("learn.command", args, ctx) },
        "reactions" to { args, ctx -> dispatch("reactions", args, ctx) },
        "mark-corrected" to { args, ctx -> dispatch("mark-corrected", args, ctx) }
    )

    /** 分派到当前进化提供者; 提供者未处理时提示。 */
    private suspend fun dispatch(command: String, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return EvolutionProviderRegistry.active().executeCommand(command, args, ctx)
            ?: ExecutionResult.fail("进化提供者未处理命令: $command", errorCode = ErrorCodes.ERR_NOT_FOUND)
    }
}
