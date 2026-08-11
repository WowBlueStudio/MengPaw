// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.security.CommandMonitor
import com.mengpaw.kernel.security.PolicyStore
import java.io.File

/**
 * Linux 命令通道 — 注册表未命中的命令执行入口 (ReAct 主循环 / Swarm worker / bang 共用)。
 *
 * 执行顺序: 点分未注册命令不落 shell → CommandMonitor (再解释 payload 递归 +
 * 规则 BLOCK/CONFIRM + 元字符 + 无参保护) → SecurityPolicy (restrictedPatterns 兜底)
 * → DefaultCommandExecutor (前缀黑名单 + 结构化元字符) → SessionShellPool。
 *
 * 安全规则与 sh -c / Termux 严格一致 — 它们只是形态不同, payload 递归进同一套检查。
 */
object LinuxCommandExecutor {

    /** 框架命令点分形态: 小写字母/数字/连字符 + 点 (agent.rea / fs.cp / a.b.c)。含 / 的路径不在此列。 */
    private val DOTTED_CMD = Regex("^[a-z0-9][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)+$")

    @Volatile
    private var monitorLoaded = false
    @Volatile
    private var monitorLastModified = 0L

    private fun ensureMonitorLoaded() {
        val file = File(DataPaths.CONFIG, "command_monitor.json")
        val mtime = try { if (file.exists()) file.lastModified() else 0L } catch (_: Exception) { 0L }
        if (monitorLoaded && mtime == monitorLastModified) return
        synchronized(this) {
            val m2 = try { if (file.exists()) file.lastModified() else 0L } catch (_: Exception) { 0L }
            if (monitorLoaded && m2 == monitorLastModified) return
            CommandMonitor.loadUserRules(file)
            monitorLoaded = true
            monitorLastModified = m2
        }
    }

    /**
     * 执行一条 Linux 命令。
     * @param allowUserConfirm 主循环 true (高危弹窗); worker 等无交互环境 false (直接拒绝)。
     */
    suspend fun execute(commandLine: String, ctx: ExecutionContext, allowUserConfirm: Boolean): ExecutionResult {
        ensureMonitorLoaded()
        val trimmed = commandLine.trim()
        val cmdName = trimmed.split(Regex("\\s+")).firstOrNull() ?: return ExecutionResult.fail(
            "空命令", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )

        // 点分未注册命令 (agent.rea 等) 不落 shell — 框架命令域, 报错附检索引导;
        // 含 / 的路径 (data/foo.sh) 是脚本/文件, 走 Linux 通道
        if (DOTTED_CMD.matches(cmdName)) {
            return ExecutionResult.fail(
                "命令未注册: $cmdName。框架命令用 self.search/self.tools 查找; Linux 命令应为无点命令名。",
                errorCode = ErrorCodes.ERR_NOT_FOUND
            )
        }

        // 1. CommandMonitor — 统一安全监控 (规则 + 弹窗 + 元字符 + 无参保护 + 再解释递归)
        CommandMonitor.evaluate(trimmed, allowUserConfirm, workDir = ctx.workDir)?.let {
            return ExecutionResult.fail(it, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }

        // 2. SecurityPolicy — restrictedPatterns 兜底 (与 Pipeline 同一共享策略)
        if (!PolicyStore.sharedPolicy().isAllowed(trimmed)) {
            return ExecutionResult.fail(
                "Command '$cmdName' is blocked by security policy",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }

        // 3. DefaultCommandExecutor — 前缀黑名单 + 结构化元字符 + 会话池执行
        val result = DefaultCommandExecutor().execute(trimmed, ctx)
        // 验证功能拉回 (v0.36.x): 重定向写操作成功后附读回验证引导 —
        // 替代已移除 agent.write 的框架自动读回验证 (裁判从框架降为提示引导)
        if (result.success) {
            CommandMonitor.extractRedirectTarget(trimmed)?.let { target ->
                val hint = "\n\n⚠️ 写操作完成，请立即 cat $target 读回验证内容一致，再继续后续步骤。"
                return ExecutionResult.ok(if (result.output.isBlank()) hint else result.output + hint)
            }
        }
        return result
    }
}
