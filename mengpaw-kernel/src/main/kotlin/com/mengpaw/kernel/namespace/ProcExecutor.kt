// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * 进程管理命名空间 — `proc.*` (kernel 常驻, 纯 JVM 实现)。
 *
 * ## 定位 (v0.48.0 定案)
 * `proc.*` 是**进程管理**, 不是"执行命令" —— 自 v0.36.x「能用 Linux 就用 Linux」整改后,
 * Agent 执行任意 shell 命令统一走 Linux 命令通道 (受
 * [com.mengpaw.kernel.security.CommandMonitor] 规则 + 弹窗约束)。再实现 `proc.exec`
 * 只会多出一条重复且更危险的路径。
 *
 * 因此提供 Linux 侧做不到的**进程查询与终止**:
 * - `proc.ps`   列出进程 (优先 ProcessHandle, Android 上降级 `/proc` 扫描)
 * - `proc.info` 单个进程详情 (命令行/父进程/用户)
 * - `proc.kill` 向进程发送终止信号 (纯 API 调用, 不拼接 shell 命令)
 *
 * ## 平台兼容 (重要)
 * `ProcessHandle` 是 **Java 9 API, Android 全平台没有** — 底层经 [ProcApi] 反射访问,
 * 平台缺类时自动降级到 `/proc` (只读) 或如实报告不可用。**不得静态引用 ProcessHandle**
 * (会导致 R8 release 构建报 Missing classes + 运行时 NoClassDefFoundError)。
 *
 * ## 安全
 * - `proc.kill` 不走 shell: 纯 API 信号调用, 无注入面, 也不绕过 CommandMonitor
 * - 拒绝终止自身 (否则当前会话必中断)
 * - 沙箱外/受保护进程失败时如实报告并引导 `root.exec`
 * - `proc.exec` / `proc.system` 是**保留位**: 不注册, 登记在
 *   [com.mengpaw.kernel.security.SecurityPolicy] 的 blockList (恒拒绝), 表达"这类能力永不开放"
 */
object ProcExecutor {

    /** 默认列出上限 — 防进程数过多灌爆上下文。 */
    private const val DEFAULT_LIMIT = 50

    /** 单条命令行显示上限。 */
    private const val CMDLINE_MAX = 120

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "ps" to ::ps,
        "info" to ::info,
        "kill" to ::kill
    )

    // ── proc.ps [--limit=N] [--filter=关键词] ────────────────────────

    private suspend fun ps(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val limit = args.find { it.startsWith("--limit=") }?.removePrefix("--limit=")?.toIntOrNull()
            ?: DEFAULT_LIMIT
        val filter = args.find { it.startsWith("--filter=") }?.removePrefix("--filter=")?.lowercase()

        val rows = collectRows()
        if (rows.isEmpty()) {
            return ExecutionResult.ok(
                "(无可枚举进程 — 当前环境限制了对进程表的访问)\n" +
                    "可尝试 Linux 命令: `ls /proc` 浏览; `cat /proc/self/status` 看自身。"
            )
        }

        val selfPid = ProcApi.currentPid()
        val filtered = rows
            .filter { filter == null || it.cmdline.lowercase().contains(filter) || it.pid.toString() == filter }
            .sortedBy { it.pid }

        val out = buildString {
            appendLine("## 进程列表 (${filtered.size}${if (filtered.size > limit) ", 显示前 $limit" else ""})")
            appendLine()
            appendLine("| PID | 命令 | 用户 |")
            appendLine("|-----|------|------|")
            filtered.take(limit).forEach { row ->
                val marker = if (row.pid == selfPid) " ← 本 Agent" else ""
                appendLine("| ${row.pid}$marker | ${row.cmdline.ifBlank { "(未知)" }} | ${row.user} |")
            }
            appendLine()
            appendLine("提示: `proc.info <pid>` 看详情; `proc.kill <pid>` 结束进程 (需 reason)。")
            if (!ProcApi.available) {
                appendLine("注: 本环境无 ProcessHandle 能力 (Android), 已降级为 /proc 扫描 — 可见进程受沙箱限制。")
            }
        }
        return ExecutionResult.ok(out)
    }

    // ── proc.info <pid> ──────────────────────────────────────────────

    private suspend fun info(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pid = args.firstOrNull()?.toLongOrNull()
            ?: return ExecutionResult.fail(
                "用法: proc.info <pid> — 查看进程详情 (pid 从 proc.ps 获取)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val detail = describePid(pid)
            ?: return ExecutionResult.fail(
                "进程不存在或不可见: pid=$pid (沙箱可能限制了对该进程的访问)",
                errorCode = ErrorCodes.ERR_NOT_FOUND
            )
        val out = buildString {
            appendLine("## 进程详情 (pid=$pid)")
            appendLine()
            appendLine("| 属性 | 值 |")
            appendLine("|------|-----|")
            appendLine("| 命令行 | ${detail.cmdline.ifBlank { "(未知)" }} |")
            appendLine("| 用户 | ${detail.user.ifBlank { "(未知)" }} |")
            appendLine("| 存活 | ${if (detail.alive) "是" else "否"} |")
            appendLine("| 启动时间 | ${detail.startInstant} |")
            appendLine("| 父进程 | ${detail.parentPid?.toString() ?: "(无)"} |")
            appendLine("| 子进程 | ${if (detail.children.isEmpty()) "(无)" else detail.children.joinToString(", ")} |")
        }
        return ExecutionResult.ok(out)
    }

    // ── proc.kill <pid> [--force] ────────────────────────────────────

    private suspend fun kill(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pid = args.firstOrNull { !it.startsWith("--") }?.toLongOrNull()
            ?: return ExecutionResult.fail(
                "用法: proc.kill <pid> [--force] — 结束进程 (pid 从 proc.ps 获取)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val force = args.any { it == "--force" }

        val selfPid = ProcApi.currentPid()
        if (selfPid > 0 && pid == selfPid) {
            return ExecutionResult.fail(
                "拒绝终止自己 (pid=$pid) —— 那会杀掉当前 Agent 会话, 任务必然中断。",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }

        val handle = ProcApi.of(pid)
        if (handle == null) {
            return ExecutionResult.fail(
                "无法定位进程 pid=$pid — 该环境无 ProcessHandle 能力 (Android) 或进程不可见。\n" +
                    "如需终止其他应用/系统进程, 请用 Root 通道: `root.exec kill $pid` (需已激活 Root 插件)。",
                errorCode = ErrorCodes.ERR_NOT_FOUND
            )
        }
        val signalled = ProcApi.destroy(handle, force)
        if (!signalled) {
            return ExecutionResult.fail(
                "终止请求被拒绝 (pid=$pid) — 该进程不属于本应用或受系统保护 (Android 沙箱限制)。\n" +
                    "如需终止系统/其他应用进程, 请用 Root 通道: `root.exec kill $pid` (需已激活 Root 插件)。",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        return ExecutionResult.ok(
            "已向 pid=$pid 发送${if (force) "强制" else ""}终止信号。\n" +
                "用 `proc.ps` 复核是否已退出 (信号送达 ≠ 立即退出)。"
        )
    }

    // ── 数据收集 (ProcessHandle 优先, /proc 降级) ───────────────────

    /** 进程行 — 降级路径也用同一结构。 */
    private data class Row(
        val pid: Long,
        val cmdline: String,
        val user: String,
        val alive: Boolean = true,
        val startInstant: String = "(未知)",
        val parentPid: Long? = null,
        val children: List<Long> = emptyList()
    )

    private fun collectRows(): List<Row> {
        val viaApi = if (ProcApi.available) {
            ProcApi.allProcesses().mapNotNull { handle -> ProcApi.snapshot(handle)?.let { s ->
                Row(s.pid, s.cmdline, s.user, s.alive, s.startInstant, s.parentPid, s.children)
            } }
        } else emptyList()
        if (viaApi.isNotEmpty()) return viaApi
        // 降级: /proc 扫描 (Android 上 ProcessHandle 不可用)
        return ProcApi.procPids().map { pid ->
            Row(pid = pid, cmdline = ProcApi.readProcCmdline(pid).take(CMDLINE_MAX), user = "")
        }
    }

    private fun describePid(pid: Long): Row? {
        if (ProcApi.available) {
            ProcApi.of(pid)?.let { handle ->
                ProcApi.snapshot(handle)?.let { s ->
                    return Row(s.pid, s.cmdline, s.user, s.alive, s.startInstant, s.parentPid, s.children)
                }
            }
        }
        // 降级: 仅 /proc 可读信息
        val cmd = ProcApi.readProcCmdline(pid)
        val exists = ProcApi.procPids().contains(pid) || cmd.isNotBlank()
        return if (exists) Row(pid, cmd.take(CMDLINE_MAX), "") else null
    }
}
