// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * self.trigger 子命令执行器 — CRON/SCHEDULE 触发器管理 (拆自 SelfExecutor,
 * 400 行文件拆分)。经 [SelfExecutor.commands]["trigger"] 委托注册。
 */
internal class SelfTriggerCommands {

    /** Trigger management. Usage: self.trigger [add|list|remove|topics|cron-wake] */
    internal suspend fun triggerCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return triggerUsage()
        val sub = args[0]
        return TRIGGER_SUBCOMMANDS[sub]?.invoke(args, ctx)
            ?: triggerUsage()
    }

    private val TRIGGER_SUBCOMMANDS: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "add" to { a, _ ->
            if (a.size < 5) ExecutionResult.fail("Usage: self.trigger add <cron|schedule> <id> <config> <action>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val engine = com.mengpaw.kernel.trigger.TriggerEngine
                val type = a[1]; val id = a[2]; val expr = a[3]; val action = a.drop(4).joinToString(" ")
                val ok = when (type) {
                    "cron" -> { engine.addCron(id, expr, action); engine.refreshCronAlarm(); true }
                    "schedule" -> { engine.addSchedule(id, expr, action); true }
                    else -> false
                }
                if (ok) ExecutionResult.ok("Trigger $id added.")
                else ExecutionResult.fail("Type must be 'cron' or 'schedule'", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
        },
        "list" to { _, _ ->
            val triggers = com.mengpaw.kernel.trigger.TriggerEngine.list()
            if (triggers.isEmpty()) ExecutionResult.ok("(No triggers)\n\n示例:\nself.trigger add cron morning-report 0 9 * * * 生成昨日摘要\nself.trigger add schedule daily-chat 08:00-22:00,count=3,interval=60 随机闲聊")
            else ExecutionResult.ok(triggers.joinToString("\n") { "${if (it.enabled) "✅" else "⛔"} ${it.id} [${it.type}] ${it.config} → ${it.action}" })
        },
        "remove" to { a, _ ->
            com.mengpaw.kernel.trigger.TriggerEngine.remove(a.getOrElse(1) { "" })
            ExecutionResult.ok("Removed.")
        },
        "topics" to { _, _ ->
            ExecutionResult.ok("## 真人感话题\n\n${com.mengpaw.kernel.trigger.TriggerEngine.SCHEDULE_TOPICS.joinToString("\n") { "- $it" }}")
        },
        "cron-wake" to { _, _ ->
            com.mengpaw.kernel.trigger.TriggerEngine.refreshCronAlarm()
            ExecutionResult.ok("Cron alarm re-registered.")
        }
    )

    private fun triggerUsage() = ExecutionResult.fail(
        "Usage: self.trigger add|list|remove|topics|cron-wake",
        errorCode = ErrorCodes.ERR_INVALID_INPUT
    )
}
