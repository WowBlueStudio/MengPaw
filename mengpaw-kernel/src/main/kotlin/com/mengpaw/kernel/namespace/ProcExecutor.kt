// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import java.io.File

/**
 * 进程管理命名空间 — `proc.*` (kernel 常驻, 纯 JVM 实现)。
 *
 * ## 定位 (v0.47.x 定案)
 * `proc.*` 是**进程管理**命令, 不是"执行命令"命令 —— 自 v0.36.x「能用 Linux 就用 Linux」整改后,
 * Agent 执行任意 shell 命令统一走 Linux 命令通道 (`sh`/`echo`/`grep` 等, 受
 * [com.mengpaw.kernel.security.CommandMonitor] 规则 + 弹窗约束)。若再实现一个 `proc.exec`
 * 执行命令, 只会多出一条重复且更危险的路径。
 *
 * 因此本命名空间提供 Linux 侧做不到的**进程查询与终止**能力:
 * - `proc.ps`   列出进程 (Java `ProcessHandle` + `/proc/<pid>/cmdline` 补名, 不依赖 shell)
 * - `proc.info` 单个进程详情 (父子关系/启动时间/命令行)
 * - `proc.kill` 向进程发送终止信号 (`ProcessHandle.destroy` / `destroyForcibly`)
 *
 * ## 安全
 * - `proc.kill` 不用 `Runtime.exec("kill ...")`: 纯 JVM API 调用, 无 shell 拼接面,
 *   不绕过 CommandMonitor (无新执行面); 沙箱内通常只能终止本应用可见的进程,
 *   系统进程会因权限失败并如实报告 (需要时引导 `root.exec` — Root 插件通道)。
 * - `proc.exec` / `proc.system` 是**保留位**: 语义为"执行任意/系统级命令", 与 Linux 通道重复,
 *   登记在 [com.mengpaw.kernel.security.SecurityPolicy] 的 blockList (恒拒绝, grant 亦不可绕过),
 *   用于表达"这类能力永不开放"。它们不是幽灵命令 —— 是显式的禁用占位。
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

    // ── proc.ps [--limit N] [--filter 关键词] ────────────────────────

    private suspend fun ps(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val limit = args.find { it.startsWith("--limit=") }?.removePrefix("--limit=")?.toIntOrNull()
            ?: DEFAULT_LIMIT
        val filter = args.find { it.startsWith("--filter=") }?.removePrefix("--filter=")?.lowercase()

        val procs = try {
            ProcessHandle.allProcesses().toList()
        } catch (e: Exception) {
            return ExecutionResult.fail("无法枚举进程: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        if (procs.isEmpty()) {
            return ExecutionResult.ok("(无可枚举进程 — 当前沙箱限制了对进程表的访问)。本机进程也可用 Linux 命令查看: ls /proc")
        }

        val selfPid = ProcessHandle.current().pid()
        val rows = procs.map { ph -> ph to describe(ph) }
            .filter { (_, d) -> filter == null || d.cmdline.lowercase().contains(filter) || d.pid.toString() == filter }
            .sortedBy { it.second.pid }

        val out = buildString {
            appendLine("## 进程列表 (${rows.size}${if (rows.size > limit) ", 显示前 $limit" else ""})")
            appendLine()
            appendLine("| PID | 命令 | 用户 |")
            appendLine("|-----|------|------|")
            rows.take(limit).forEach { (ph, d) ->
                val marker = if (d.pid == selfPid) " ← 本 Agent" else ""
                appendLine("| ${d.pid}$marker | ${d.cmdline.ifBlank { "(未知)" }} | ${d.user} |")
            }
            appendLine()
            appendLine("提示: `proc.info <pid>` 看详情; `proc.kill <pid>` 结束进程 (需 reason); 无参 `ls /proc` 也可浏览。")
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
        val ph = ProcessHandle.of(pid).orElse(null)
            ?: return ExecutionResult.fail(
                "进程不存在或不可见: pid=$pid (沙箱可能限制了对该进程的访问)",
                errorCode = ErrorCodes.ERR_NOT_FOUND
            )
        val d = describe(ph)
        val out = buildString {
            appendLine("## 进程详情 (pid=$pid)")
            appendLine()
            appendLine("| 属性 | 值 |")
            appendLine("|------|-----|")
            appendLine("| 命令行 | ${d.cmdline.ifBlank { "(未知)" }} |")
            appendLine("| 用户 | ${d.user} |")
            appendLine("| 存活 | ${if (d.alive) "是" else "否"} |")
            appendLine("| 启动时间 | ${d.startInstant} |")
            appendLine("| 父进程 | ${d.parentPid?.toString() ?: "(无)"} |")
            appendLine("| 子进程 | ${if (d.children.isEmpty()) "(无)" else d.children.joinToString(", ")} |")
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

        val selfPid = ProcessHandle.current().pid()
        if (pid == selfPid) {
            return ExecutionResult.fail(
                "拒绝终止自己 (pid=$pid) —— 那会杀掉当前 Agent 会话, 任务必然中断。",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        val ph = ProcessHandle.of(pid).orElse(null)
            ?: return ExecutionResult.fail(
                "进程不存在或不可见: pid=$pid (沙箱可能限制了对该进程的访问)",
                errorCode = ErrorCodes.ERR_NOT_FOUND
            )

        // 纯 JVM API 终止 — 不拼接 shell 命令, 无注入面, 也不绕过 CommandMonitor
        val signalled = try {
            if (force) ph.destroyForcibly() else ph.destroy()
        } catch (e: Exception) {
            return ExecutionResult.fail(
                "终止失败 (pid=$pid): ${e.message}",
                errorCode = ErrorCodes.ERR_INTERNAL
            )
        }
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

    // ── 描述提取 ─────────────────────────────────────────────────────

    private data class ProcDesc(
        val pid: Long,
        val cmdline: String,
        val user: String,
        val alive: Boolean,
        val startInstant: String,
        val parentPid: Long?,
        val children: List<Long>
    )

    /**
     * 汇总一个进程的可读信息。
     * 进程名优先取 `/proc/<pid>/cmdline` (Linux/Android 可用), 退化用 `ProcessHandle.info().command`。
     */
    private fun describe(ph: ProcessHandle): ProcDesc {
        // ProcessHandle API 的 Kotlin 映射: info()/parent() 为平台类型 (可能 null),
        // 而 Info.command()/arguments()/user()/startInstant() 返回 Java Optional — 逐项显式取值。
        val info: ProcessHandle.Info? = runCatching { ph.info() }.getOrNull()
        val cmdFromInfo: String = info?.command()?.orElse("") ?: ""
        val args: String = info?.arguments()?.orElse(null)?.joinToString(" ") ?: ""
        val user: String = info?.user()?.orElse("") ?: ""
        val start: String = info?.startInstant()?.orElse(null)?.toString() ?: "(未知)"
        val cmdFromProc = readProcCmdline(ph.pid())
        val cmdline = when {
            cmdFromProc.isNotBlank() -> cmdFromProc
            args.isNotBlank() -> "$cmdFromInfo $args".trim()
            else -> cmdFromInfo
        }
        val alive: Boolean = runCatching { ph.isAlive }.getOrDefault(false)
        val parentPid: Long? = try {
            val p = ph.parent()
            if (p != null && p.isPresent) p.get().pid() else null
        } catch (_: Exception) {
            null
        }
        val children: List<Long> = try {
            ph.children().map { child: ProcessHandle -> child.pid() }.collect(java.util.stream.Collectors.toList())
        } catch (_: Exception) {
            emptyList()
        }
        return ProcDesc(
            pid = ph.pid(),
            cmdline = cmdline.take(CMDLINE_MAX).replace('\n', ' '),
            user = user,
            alive = alive,
            startInstant = start,
            parentPid = parentPid,
            children = children
        )
    }

    /** 读取 /proc/<pid>/cmdline (NUL 分隔) — 失败返回空串 (Android 上部分进程不可读)。 */
    private fun readProcCmdline(pid: Long): String = try {
        val f = File("/proc/$pid/cmdline")
        if (f.exists() && f.canRead()) {
            f.readText().replace('\u0000', ' ').trim()
        } else ""
    } catch (_: Exception) {
        ""
    }
}
