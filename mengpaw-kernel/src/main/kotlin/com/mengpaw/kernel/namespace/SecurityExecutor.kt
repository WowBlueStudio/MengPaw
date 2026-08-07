// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.security.SourceBlocklist

/**
 * Security namespace — 攻击来源黑名单管理 (v0.34.1, ⑦ 拉黑闭环)。
 *
 * 目的明确的提示词攻击 (InjectionPatterns.findMatch 命中) 会触发系统提醒并询问用户
 * 是否拉黑来源; 用户确认后执行 `security.block <来源>` 持久化黑名单 (blocklist.json),
 * 后续同来源内容直接阻止, 不进上下文。
 *
 * 命令:
 *   security.block <来源>     — 将来源 (域名/路径) 加入黑名单
 *   security.unblock <来源>   — 从黑名单移除
 *   security.blocklist        — 列出全部黑名单条目
 */
object SecurityExecutor {

    val commands = mapOf(
        "block" to ::block,
        "unblock" to ::unblock,
        "blocklist" to ::blocklist
    )

    private suspend fun block(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val entry = args.getOrNull(0)?.trim()
        if (entry.isNullOrEmpty()) {
            return ExecutionResult.fail("用法: security.block <来源> — 将攻击来源 (域名/路径) 加入黑名单",
                errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        if (entry.length > 128 || entry.any { it.code < 0x20 }) {
            return ExecutionResult.fail("非法来源: 长度 ≤128 且不得含控制字符", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        if (SourceBlocklist.isBlocked(entry)) {
            return ExecutionResult.ok("来源 $entry 已在黑名单")
        }
        val persisted = SourceBlocklist.block(entry)
        val list = SourceBlocklist.list()
        return if (persisted) {
            ExecutionResult.ok("已拉黑来源: $entry (黑名单 ${list.size} 条)")
        } else {
            ExecutionResult.ok("已拉黑来源: $entry (持久化失败, 仅本次运行生效; 黑名单 ${list.size} 条)")
        }
    }

    private suspend fun unblock(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val entry = args.getOrNull(0)?.trim()
        if (entry.isNullOrEmpty()) {
            return ExecutionResult.fail("用法: security.unblock <来源> — 从黑名单移除来源",
                errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        if (!SourceBlocklist.isBlocked(entry)) {
            return ExecutionResult.ok("来源 $entry 不在黑名单中")
        }
        val persisted = SourceBlocklist.unblock(entry)
        return if (persisted) {
            ExecutionResult.ok("已解除拉黑: $entry")
        } else {
            ExecutionResult.ok("已解除拉黑: $entry (持久化失败, 仅本次运行生效)")
        }
    }

    private suspend fun blocklist(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val list = SourceBlocklist.list()
        if (list.isEmpty()) {
            return ExecutionResult.ok("黑名单为空 — 无攻击来源被阻止")
        }
        return ExecutionResult.ok(buildString {
            appendLine("攻击来源黑名单 (${list.size} 条):")
            list.forEach { appendLine("- $it") }
            appendLine("\n移除: security.unblock <来源>")
        })
    }
}
