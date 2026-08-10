// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.root

import android.content.pm.PackageManager
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.*
import java.io.File

/**
 * Root Plugin — privileged Android system access for Agent.
 *
 * Provides CLI commands for root-level operations:
 * - Root detection & status
 * - Command execution (su -c) with safety guards and audit logging
 * - App management (list/freeze/unfreeze/uninstall)
 * - Full filesystem access (bypass Scoped Storage)
 * - System modification (props, hosts)
 * - App data backup and restore
 */
class RootPlugin : Plugin {

    override suspend fun onInstall(ctx: PluginContext) {
        // Inject first-use safety warning into agent inbox
        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            val warningFile = java.io.File(inbox, "root_plugin_activated.md")
            java.io.File(inbox, "root_plugin_activated.tmp").writeText("""
# ⚠️ Root 插件已激活

你现在拥有设备的 **最高 root 权限**。

## 你可以
- root.exec — 以 root 身份执行任意 shell 命令
- root.apps.* — 列出/冻结/卸载任何应用（含系统应用）
- root.fs.* — 读写任何文件（绕过所有权限限制）
- root.system.* — 修改系统属性和 hosts
- root.backup.* — 备份/恢复应用数据

## 安全护栏（自动执行，你不需要操心）
- 危险命令自动拦截: rm -rf /, dd to /dev, mkfs, 自毁
- 所有命令记录在审计日志: root.audit 查看
- 完整输出保存: agent.read /sdcard/root_out.txt

## 你的责任
- ⚠️ 执行任何 root 操作前，确认其安全性
- ⚠️ 不要删除系统关键文件
- ⚠️ 不要修改其他应用的私密数据
- ⚠️ 不确定的操作先问用户

**root.status** — 随时检查 root 状态
""".trimIndent())
            java.io.File(inbox, "root_plugin_activated.tmp").renameTo(warningFile)
        } catch (_: Exception) {}
    }

    override val metadata = PluginMetadata(
        id = "root-plugin",
        name = "Root 权限",
        version = "", // 内置插件, 随 Shell APK 版本更新
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Root 权限管理 — su 命令执行/应用管理/文件系统/系统修改/备份恢复/审计日志",
        minCoreVersion = "0.15.0",
        commands = listOf(
            "root.status",
            "root.exec", "root.shell",
            "root.apps.list", "root.apps.freeze", "root.apps.unfreeze", "root.apps.uninstall", "root.apps.data",
            "root.fs.ls", "root.fs.cat", "root.fs.write", "root.fs.stat",
            "root.system.props", "root.system.setprop", "root.system.hosts",
            "root.backup.list", "root.backup.save", "root.backup.restore",
            "root.audit"
        )
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "status" to ::status,
        "exec" to ::exec,
        "shell" to ::shell,
        "apps.list" to ::appsList,
        "apps.freeze" to ::appsFreeze,
        "apps.unfreeze" to ::appsUnfreeze,
        "apps.uninstall" to ::appsUninstall,
        "apps.data" to ::appsData,
        "fs.ls" to ::fsLs,
        "fs.cat" to ::fsCat,
        "fs.write" to ::fsWrite,
        "fs.stat" to ::fsStat,
        "system.props" to ::systemProps,
        "system.setprop" to ::systemSetprop,
        "system.hosts" to ::systemHosts,
        "backup.list" to ::backupList,
        "backup.save" to ::backupSave,
        "backup.restore" to ::backupRestore,
        "audit" to ::audit,
    )

    // ═══════════════════════════════════════════════════════════════
    // Detection
    // ═══════════════════════════════════════════════════════════════

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val s = RootDetector.detect()
        val working = RootShell.checkSu()
        val extra = if (working.first) "\n- su 验证: ✅ uid=0 可用\n${working.second}" else ""
        return ExecutionResult.ok(s.summary + extra)
    }

    // ═══════════════════════════════════════════════════════════════
    // Command execution
    // ═══════════════════════════════════════════════════════════════

    private suspend fun exec(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "用法: root.exec <命令>\n以 root 权限执行单条 shell 命令。输出截断为 4000 字符，完整输出在 /sdcard/root_out.txt\n所有命令记录在审计日志中。"
        )
        val command = args.joinToString(" ")
        val result = RootShell.execute(command)
        val extra = if (result.stdout.length > 4000)
            "\n\n📄 完整输出: agent.read /sdcard/root_out.txt" else ""
        return ExecutionResult.ok(buildString {
            appendLine(result.summary)
            if (extra.isNotBlank()) appendLine(extra)
            appendLine()
            appendLine("📋 查看审计: root.audit --last 5")
            appendLine("📄 完整输出: agent.read /sdcard/root_out.txt")
            appendLine("📊 设备状态: root.status")
        })
    }

    private suspend fun shell(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val s = RootDetector.detect()
        return if (s.suPath != null) {
            ExecutionResult.ok(buildString {
                appendLine("su: ${s.suPath}")
                if (s.suVersion != null) appendLine("版本: ${s.suVersion}")
                appendLine()
                appendLine("用法: root.exec <命令>")
                appendLine("示例: root.exec \"ls -la /data/data\"")
                appendLine("示例: root.exec \"pm list packages -d\"")
            })
        } else ExecutionResult.fail("未检测到 su。此设备可能未 Root。运行 root.status 查看详情。")
    }

    // ═══════════════════════════════════════════════════════════════
    // App management
    // ═══════════════════════════════════════════════════════════════

    private suspend fun appsList(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flag = when {
            args.contains("--system") -> "-s"
            args.contains("--user") || args.contains("--thirdparty") -> "-3"
            args.contains("--disabled") -> "-d"
            else -> ""
        }
        val result = RootShell.execute("pm list packages $flag")
        val packages = result.stdout.lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:") }
        if (packages.isEmpty()) return ExecutionResult.ok(result.summary)
        return ExecutionResult.ok(buildString {
            appendLine("## 应用列表 (${packages.size} 个)")
            if (flag.isNotBlank()) appendLine("> 过滤: $flag")
            appendLine()
            packages.forEach { appendLine("- $it") }
        })
    }

    private suspend fun appsFreeze(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.apps.freeze <包名>")
        // P1 修复: 参数经 shellQuote 转义, 防止注入 (; && $() 反引号等)
        val result = RootShell.execute("pm disable ${RootShell.shellQuote(pkg)}")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun appsUnfreeze(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.apps.unfreeze <包名>")
        val result = RootShell.execute("pm enable ${RootShell.shellQuote(pkg)}")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun appsUninstall(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.apps.uninstall <包名>")
        val result = RootShell.execute("pm uninstall --user 0 ${RootShell.shellQuote(pkg)}")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun appsData(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.apps.data <包名>")
        val result = RootShell.execute("du -sh ${RootShell.shellQuote("/data/data/$pkg")} 2>/dev/null && ls -la ${RootShell.shellQuote("/data/data/$pkg/")} 2>/dev/null | head -20")
        return ExecutionResult.ok(result.summary)
    }

    // ═══════════════════════════════════════════════════════════════
    // File system
    // ═══════════════════════════════════════════════════════════════

    private suspend fun fsLs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.fs.ls <路径>")
        // P1 修复: 参数经 shellQuote 转义, 防止注入
        val result = RootShell.execute("ls -la ${RootShell.shellQuote(path)} 2>&1 | head -50")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun fsCat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.fs.cat <路径>")
        val result = RootShell.execute("cat ${RootShell.shellQuote(path)} 2>&1")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun fsWrite(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: root.fs.write <路径> <内容>")
        val path = args.first()
        val content = args.drop(1).joinToString(" ")
        // P1 修复: 内容与路径均经 shellQuote 转义 (单引号包裹天然免疫 $() 反引号等)
        val result = RootShell.execute("echo ${RootShell.shellQuote(content)} > ${RootShell.shellQuote(path)} 2>&1 && echo 'WRITE_OK'")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun fsStat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.fs.stat <路径>")
        val result = RootShell.execute("stat ${RootShell.shellQuote(path)} 2>&1; echo '---'; ls -laZ ${RootShell.shellQuote(path)} 2>&1")
        return ExecutionResult.ok(result.summary)
    }

    // ═══════════════════════════════════════════════════════════════
    // System
    // ═══════════════════════════════════════════════════════════════

    private suspend fun systemProps(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val key = args.firstOrNull()
        // P1 修复: 参数经 shellQuote 转义, 防止注入
        val cmd = if (key != null) "getprop ${RootShell.shellQuote(key)}" else "getprop"
        val result = RootShell.execute(cmd)
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun systemSetprop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: root.system.setprop <key> <value>")
        val result = RootShell.execute("setprop ${RootShell.shellQuote(args[0])} ${RootShell.shellQuote(args[1])} 2>&1 && echo 'PROP_SET_OK'")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun systemHosts(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            val result = RootShell.execute("cat /system/etc/hosts 2>&1 | head -30")
            return ExecutionResult.ok(result.summary)
        }
        val action = args[0]
        return when (action) {
            "add" -> {
                if (args.size < 3) return ExecutionResult.fail("用法: root.system.hosts add <域名> <IP>")
                val domain = args[1]; val ip = args[2]
                // P1 修复: 域名/IP 经 shellQuote 转义, 防止注入
                val result = RootShell.execute("echo ${RootShell.shellQuote("$ip $domain")} >> /system/etc/hosts 2>&1 && echo 'HOSTS_ADD_OK'")
                ExecutionResult.ok(result.summary)
            }
            "remove" -> {
                if (args.size < 2) return ExecutionResult.fail("用法: root.system.hosts remove <域名>")
                val domain = args[1]
                // P1 修复: sed 模式转义 (域名中的 / 需转义) + shellQuote 防注入
                val sedPattern = "/" + domain.replace("/", "\\/") + "/d"
                val result = RootShell.execute("sed -i ${RootShell.shellQuote(sedPattern)} /system/etc/hosts 2>&1 && echo 'HOSTS_REMOVE_OK'")
                ExecutionResult.ok(result.summary)
            }
            else -> ExecutionResult.fail("用法: root.system.hosts [add|remove] <域名> [IP]")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Backup / restore
    // ═══════════════════════════════════════════════════════════════

    private suspend fun backupList(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.backup.list <包名>")
        // P1 修复: 参数经 shellQuote 转义, 防止注入
        val result = RootShell.execute("ls -laR ${RootShell.shellQuote("/data/data/$pkg/")} 2>&1 | head -40")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun backupSave(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("用法: root.backup.save <包名> [--to <路径>]")
        val dest = args.find { it.startsWith("--to") }?.substringAfter("--to")?.trim()
            ?: "/sdcard/${pkg}_backup_${System.currentTimeMillis().toString().takeLast(6)}.tar.gz"
        val result = RootShell.execute("cd /data/data && tar czf ${RootShell.shellQuote(dest)} ${RootShell.shellQuote(pkg)} 2>&1 && echo ${RootShell.shellQuote("BACKUP_OK: $dest")}")
        return ExecutionResult.ok(result.summary)
    }

    private suspend fun backupRestore(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: root.backup.restore <包名> --from <路径>")
        val pkg = args[0]
        val src = args.find { it.startsWith("--from") }?.substringAfter("--from")?.trim()
            ?: return ExecutionResult.fail("需要 --from <路径> 指定备份文件")
        val result = RootShell.execute("cd /data/data && tar xzf ${RootShell.shellQuote(src)} 2>&1 && echo ${RootShell.shellQuote("RESTORE_OK: $pkg")}")
        return ExecutionResult.ok(result.summary)
    }

    // ═══════════════════════════════════════════════════════════════
    // Audit
    // ═══════════════════════════════════════════════════════════════

    private suspend fun audit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val last = args.find { it.startsWith("--last") }?.substringAfter("--last")?.trim()?.toIntOrNull() ?: 50
        val file = File(DataPaths.BASE, "root_audit.log")
        if (!file.exists()) return ExecutionResult.ok("(审计日志为空)")
        val lines = try { file.readLines() } catch (_: Exception) { emptyList() }
        val recent = lines.takeLast(last)
        if (recent.isEmpty()) return ExecutionResult.ok("(审计日志为空)")
        return ExecutionResult.ok(buildString {
            appendLine("## Root 审计日志 (最近 ${recent.size} 条)")
            appendLine()
            appendLine("| 时间 | 状态 | 退出码 | 耗时 |")
            appendLine("|------|------|:--:|------|")
            recent.reversed().forEach { line ->
                val parts = line.split(" | ")
                if (parts.size >= 4) {
                    appendLine("| ${parts[0]} | ${parts[1]} | ${parts.getOrNull(3)?.removePrefix("exit=") ?: "-"} | ${parts.getOrNull(4) ?: "-"} |")
                }
            }
        })
    }
}
