// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.cli.DefaultCommandExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Linux 命令通道统一安全监控 — 直接命令 / sh -c / Termux 三种形态共用同一套规则。
 *
 * 注册表未命中的命令进入 Linux 通道前一律先过本监控:
 * 1. 再解释形态 (sh -c / am startservice→Termux / su -c) → 提取 payload 递归再入同一套检查;
 * 2. 结构化元字符检查 + 危险工具前缀黑名单 (复用 [DefaultCommandExecutor.sandboxCheck]);
 * 3. 无参 stdin 命令预检 (防 grep/cat 无参挂起 30s);
 * 4. 规则匹配: BLOCK 直接拒绝, CONFIRM 弹窗 ([UserConfirmBus], 30s 超时默认拒绝)。
 *
 * 规则 = 内置默认规则 + {BASE}/配置/command_monitor.json 追加覆盖 (同名 id 覆盖内置,
 * 损坏/缺失文件忽略, 安全默认)。用户规则文件可自定义放行 (level=OFF 不支持 —
 * 只支持 BLOCK/CONFIRM, 避免把高危降级)。
 */
object CommandMonitor {

    enum class Level { BLOCK, CONFIRM }

    data class Rule(val id: String, val pattern: Regex, val level: Level)

    private const val MAX_RECURSE_DEPTH = 2
    private const val CONFIRM_TIMEOUT_MS = 30_000L

    /** 再解释 shell 命令名 (含绝对路径形态, 按 basename 匹配)。 */
    private val SHELL_NAMES = setOf("sh", "bash", "ash", "busybox")

    /** 无参会从 stdin 等待输入导致挂起的命令 — 预检拒绝。 */
    private val STDIN_CMDS = setOf(
        "cat", "grep", "head", "tail", "sed", "sort", "uniq", "wc",
        "find", "less", "more", "read", "tr", "cut", "paste", "awk", "xargs", "tac"
    )

    /**
     * 内置默认规则 — 随 APK 升级可控; 用户规则文件同名 id 覆盖。
     * 高危清单 (用户定案, 不增减): rm 删除 / 覆盖写系统路径 / chmod 改权限 /
     * 下载并执行 / 格式化分区 / 关机重启。
     */
    private val DEFAULT_RULES = listOf(
        // ── BLOCK: 直接拒绝, 不进弹窗 ──
        Rule("rm-rf-root", Regex("""\brm\s+(?:-[a-zA-Z]*[rf][a-zA-Z]*\s+)*/(?:\s|$)"""), Level.BLOCK),
        Rule("mkfs", Regex("""\bmkfs\b"""), Level.BLOCK),
        Rule("dd-dev", Regex("""\bdd\s+if=.*of=/dev/"""), Level.BLOCK),
        Rule("download-exec", Regex("""(?:curl|wget)\b.*\|\s*(?:sh|bash|zsh|python|perl|ruby)\b"""), Level.BLOCK),
        Rule("overwrite-system", Regex(""">\s*/(?:etc|dev|system|proc|sys)(?:/|\b)"""), Level.BLOCK),
        Rule("su-sudo", Regex("""(?:^|[^A-Za-z0-9_])(?:su|sudo)\b"""), Level.BLOCK),
        // ── CONFIRM: 弹窗询问用户, 同意后继续 ──
        Rule("rm", Regex("""\brm\s+"""), Level.CONFIRM),
        Rule("chmod", Regex("""\bchmod\b"""), Level.CONFIRM),
        Rule("chown", Regex("""\bchown\b"""), Level.CONFIRM),
        Rule("shutdown", Regex("""\b(?:shutdown|reboot|poweroff|halt)\b"""), Level.CONFIRM)
    )

    @Volatile
    private var userRules: Map<String, Rule> = emptyMap()
    private val lock = Any()

    // ── 规则文件加载 ─────────────────────────────────────────────

    /** 从规则文件加载用户规则 (同名 id 覆盖内置; 损坏/缺失忽略, 安全默认)。 */
    fun loadUserRules(file: File) {
        try {
            if (!file.exists()) return
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val rulesArr = root["rules"]?.jsonArray ?: return
            val loaded = rulesArr.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val level = when (obj["level"]?.jsonPrimitive?.contentOrNull) {
                    "BLOCK" -> Level.BLOCK
                    "CONFIRM" -> Level.CONFIRM
                    else -> return@mapNotNull null
                }
                val regex = try { Regex(pattern) } catch (_: Exception) { return@mapNotNull null }
                id to Rule(id, regex, level)
            }.toMap()
            synchronized(lock) { userRules = loaded }
        } catch (_: Exception) {
            // 损坏/缺失 — 保持内置规则
        }
    }

    /** 生效规则 = 内置 + 用户覆盖。 */
    private fun activeRules(): List<Rule> {
        val user = userRules
        if (user.isEmpty()) return DEFAULT_RULES
        val merged = LinkedHashMap<String, Rule>()
        DEFAULT_RULES.forEach { merged[it.id] = it }
        user.forEach { (id, rule) -> merged[id] = rule }
        return merged.values.toList()
    }

    /** 测试隔离用 — 清空用户规则 (恢复纯内置)。 */
    fun resetForTest() {
        synchronized(lock) { userRules = emptyMap() }
    }

    // ── 入口 ─────────────────────────────────────────────────────

    /**
     * 评估一条待执行命令。返回拒绝原因 (应阻止执行) 或 null (放行)。
     * @param allowUserConfirm 主循环 true (可弹窗); worker 等无交互环境 false (高危直接拒绝)。
     */
    suspend fun evaluate(commandLine: String, allowUserConfirm: Boolean): String? =
        evaluateInternal(commandLine.trim(), allowUserConfirm, 0)

    private suspend fun evaluateInternal(cmd: String, allowUserConfirm: Boolean, depth: Int): String? {
        if (cmd.isBlank()) return null

        // 1. 再解释形态 → 提取 payload 递归 (su/sudo 直接 BLOCK)
        when (val re = detectReinterpret(cmd)) {
            is Reinterpret.Blocked -> return "提权命令 (su/sudo) 已被安全策略禁止: $cmd"
            is Reinterpret.Recurse -> {
                if (depth >= MAX_RECURSE_DEPTH) {
                    return "再解释嵌套超过 $MAX_RECURSE_DEPTH 层，已阻止: $cmd"
                }
                if (re.payload.isBlank()) {
                    return "再解释命令缺少执行内容，已阻止: $cmd"
                }
                return evaluateInternal(re.payload, allowUserConfirm, depth + 1)
            }
            Reinterpret.None -> {}
        }

        // 2. 危险工具前缀黑名单 + 结构化元字符 (管道放行; 多命令串接/变量/命令替换拦截)
        DefaultCommandExecutor.sandboxCheck(cmd)?.let { return it }

        // 3. 无参 stdin 命令预检 (防挂起)
        checkStdin(cmd)?.let { return it }

        // 4. 规则匹配: BLOCK 优先, CONFIRM 弹窗
        return matchRules(cmd, allowUserConfirm)
    }

    // ── 再解释形态检测 ───────────────────────────────────────────

    private sealed class Reinterpret {
        object None : Reinterpret()
        object Blocked : Reinterpret()
        class Recurse(val payload: String) : Reinterpret()
    }

    /** 识别 sh -c / Termux am startservice / su -c 三种再解释形态。 */
    private fun detectReinterpret(cmd: String): Reinterpret {
        val tokens = tokenize(cmd)
        if (tokens.isEmpty()) return Reinterpret.None
        val base = tokens[0].substringAfterLast('/')

        // su / sudo — 提权通道, 直接 BLOCK
        if (base == "su" || base == "sudo") return Reinterpret.Blocked

        // sh|bash|ash|busybox -c <payload> (含绝对路径形态)
        if (base in SHELL_NAMES) {
            val cIdx = tokens.indexOf("-c")
            if (cIdx >= 0 && cIdx + 1 < tokens.size) {
                // POSIX: sh -c <command_string> [name ...] — 只取 -c 后第一个 token
                // (引号包裹的整串 payload 经 tokenize 已合并为一个 token)
                return Reinterpret.Recurse(tokens[cIdx + 1])
            }
        }

        // Termux: am startservice ... com.termux.RUN_COMMAND_ARGUMENTS <payload>
        if (tokens.any { it == "com.termux.RUN_COMMAND_ARGUMENTS" }) {
            val argsIdx = tokens.indexOf("com.termux.RUN_COMMAND_ARGUMENTS")
            if (argsIdx >= 0 && argsIdx + 1 < tokens.size) {
                // --esa 的值是逗号分隔的参数数组, 技能约定整体用引号包裹 → 一个 token;
                // 后面的 --ez/--es 是 am 的其他 intent 参数, 不得并入 payload
                val raw = tokens[argsIdx + 1]
                val payload = if (raw.startsWith("-c,")) raw.removePrefix("-c,") else raw
                return Reinterpret.Recurse(payload)
            }
        }

        return Reinterpret.None
    }

    /** 引号感知 tokenizer (支持单/双引号 + 反斜杠转义, 与 shell 语义对齐)。 */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote: Char? = null
        var escape = false
        for (c in input) {
            when {
                escape -> { cur.append(c); escape = false }
                c == '\\' -> escape = true
                inQuote != null -> {
                    if (c == inQuote) inQuote = null else cur.append(c)
                }
                c == '\'' || c == '"' -> inQuote = c
                c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { tokens.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        return tokens
    }

    // ── 无参 stdin 保护 ──────────────────────────────────────────

    private fun checkStdin(cmd: String): String? {
        val m = Regex("""^\s*([A-Za-z0-9_./-]+)\s*(.*)$""").find(cmd) ?: return null
        val name = m.groupValues[1].substringAfterLast('/')
        if (name in STDIN_CMDS && m.groupValues[2].isBlank()) {
            return "命令 '$name' 缺少参数（会从 stdin 等待输入导致挂起）。请补参数或用管道: $cmd"
        }
        return null
    }

    // ── 规则匹配 ─────────────────────────────────────────────────

    private suspend fun matchRules(cmd: String, allowUserConfirm: Boolean): String? {
        val rules = activeRules()
        rules.firstOrNull { it.level == Level.BLOCK && it.pattern.containsMatchIn(cmd) }?.let {
            return "命令命中安全规则 [${it.id}]，已阻止: $cmd"
        }
        val confirmHits = rules.filter { it.level == Level.CONFIRM && it.pattern.containsMatchIn(cmd) }
        if (confirmHits.isNotEmpty()) {
            if (!allowUserConfirm) {
                return "命令命中高危规则 [${confirmHits.first().id}]，当前环境不弹窗确认，已阻止: $cmd"
            }
            val allowed = UserConfirmBus.request(cmd, null, "高危", CONFIRM_TIMEOUT_MS)
            if (!allowed) return "用户拒绝了高危命令: $cmd"
        }
        return null
    }
}
