// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.llm.ToolCall

/**
 * 中危/高危命令 reason 门禁 (P0 注入防护 v0.34.1, 分级化 v0.34.3)。
 *
 * Agent 自主调用中危/高危命令（删除/修改、proc、插件管理、剪贴板、记忆删改、
 * root.*、sys 破坏性）必须附意图声明 (reason)。形态 = **JSON 豁免通道**:
 * 高危命令豁免 [ToolCall.paramFormatError] 全局门卫, 允许结构化 `{"reason": ...}` 参数;
 * 非高危命令维持原门卫 (行为零变化)。普通 (LOW) 命令已移出本表 — 不再强制 reason,
 * 由 CommandRiskLevels 分级决定是否拦截 (LOW 放行 / MID 权限 / HIGH 弹窗)。
 *
 * 模板驱动展开: 参数按模板键序拼接 (reason 及模板外键一律排除), 消除 JSON 键序
 * 不稳定导致的参数错位。豁免条件 = 名在高危表 **且** reason 非空 — 模板漏配 =
 * 该命令不可 JSON 调用, 维持 paramFormatError (默认安全)。
 *
 * reason 是意图声明 (软信息, 模型自述), 硬层只保证存在性; 真实性靠系统提示词
 * 信任边界教学。reason 不进入执行命令文本, 由调用方写入审计。
 */
object HighRiskCommandGate {

    enum class Kind { POSITIONAL, FLAG }

    /** 参数模板条目: POSITIONAL 按序拼接为位置参数; FLAG 值为 "true" 时拼 "--key"。 */
    data class Param(val key: String, val kind: Kind = Kind.POSITIONAL)

    /** 中危/高危命令 → 参数模板 (v0.34.3: LOW 命令移出 — 普通写操作不再强制 reason)。
     *  集合按真实注册表校准 (fs.write 已并入 agent.*, 不在此列)。 */
    val HIGH_RISK: Map<String, List<Param>> = mapOf(
        // ── 文件 (中危; agent.rm/fs.mv 已随 Linux 通道移除, Linux rm/mv 走 CommandMonitor CONFIRM) ──
        // ── 进程 ──
        "proc.exec" to listOf(Param("command")),
        "proc.system" to listOf(Param("command")),
        "proc.kill" to listOf(Param("pid")),
        // ── 插件管理 (install/enable/disable/update 中危, uninstall 高危) ──
        "plugin.install" to listOf(Param("id")),
        "plugin.uninstall" to listOf(Param("id")),
        "plugin.enable" to listOf(Param("id")),
        "plugin.disable" to listOf(Param("id")),
        "plugin.update" to listOf(Param("id")),
        // ── 剪贴板 (copy/paste 中危, clear 高危) ──
        "clipboard.copy" to listOf(Param("text")),
        "clipboard.paste" to emptyList(),
        "clipboard.clear" to emptyList(),
        // ── Office 文档修改 (中危: docx 追加 / xlsx 写单元格) ──
        "office.write" to listOf(Param("path"), Param("content")),
        // ── 技能开关 (中危) ──
        "skill.enable" to listOf(Param("id")),
        "skill.disable" to listOf(Param("id")),
        // ── 技能流转 (中危: 派生写技能文件 / 索取复制技能) ──
        "skill.from.project" to listOf(Param("name")),
        "skill.request" to listOf(Param("name"), Param("agent")),
        "skill.import" to listOf(Param("name")),
        // ── 记忆改/删 (keep/write/record/project.save 为 LOW, 不入表) ──
        "agent.memory.rm" to listOf(Param("timestamp")),
        "agent.memory.edit" to listOf(Param("timestamp"), Param("content")),
        "agent.memory.mid.delete" to listOf(Param("date")),
        "agent.memory.mid.rm" to listOf(Param("date"), Param("timestamp")),
        "agent.memory.mid.edit" to listOf(Param("date"), Param("timestamp"), Param("content")),
        "agent.memory.project.delete" to listOf(Param("project")),
        "agent.memory.project.rm" to listOf(Param("project"), Param("timestamp")),
        "agent.memory.project.edit" to listOf(Param("project"), Param("timestamp"), Param("content")),
        // ── root-plugin (安装后可用) ──
        "root.exec" to listOf(Param("command")),
        "root.shell" to listOf(Param("command")),
        "root.fs.write" to listOf(Param("path"), Param("content")),
        "root.system.setprop" to listOf(Param("key"), Param("value")),
        "root.system.hosts" to emptyList(),
        "root.backup.restore" to listOf(Param("path")),
        "root.apps.uninstall" to listOf(Param("package")),
        "root.apps.freeze" to listOf(Param("package")),
        "root.apps.unfreeze" to listOf(Param("package"))
    )

    /** 门禁求值结果: 展开后的命令行 (不含 reason) + 可选拒绝错误 + 审计用 reason。 */
    data class GateResult(
        val commandLine: String,
        val error: String? = null,
        val errorCode: String? = null,
        /** 高危命令的意图声明 (审计用, 不进入命令文本)。非高危/被拒为 null。 */
        val reason: String? = null
    )

    /**
     * 门禁求值 (纯函数, 无副作用):
     * - 非高危命令 → 原 paramFormatError 原样透传 (行为零变化)
     * - 高危命令 → 必须带非空 reason, 否则 REASON_REQUIRED + 按模板动态生成的 JSON 示例;
     *   带 reason → 按模板键序展开, reason/模板外键排除, 缺键报 PARAM_FORMAT_ERROR。
     */
    fun evaluate(call: ToolCall): GateResult {
        val template = HIGH_RISK[call.name]
        if (template == null) {
            // 非高危: 维持原门卫与命令行构造
            val formatError = call.paramFormatError()
            return GateResult(
                commandLine = "${call.name} ${call.parameters.values.joinToString(" ")}",
                error = formatError,
                errorCode = if (formatError != null) ErrorCodes.PARAM_FORMAT_ERROR else null
            )
        }
        // ── 高危命令: 意图声明硬检查 ──
        val reason = call.parameters["reason"]?.trim()
        if (reason.isNullOrEmpty()) {
            return GateResult(
                commandLine = "${call.name} ${call.parameters.values.joinToString(" ")}",
                // 注: 不加 "Error [code]: " 前缀 — Observation 组装层会按 errorCode 统一加
                error = "命令 '${call.name}' 属于高危操作" +
                    "（影响文件/系统/剪贴板等），需要意图声明。请在参数中提供 \"reason\"（目的/理由）后重试。\n" +
                    "示例: ${jsonExample(call.name)}",
                errorCode = ErrorCodes.REASON_REQUIRED
            )
        }
        // ── 模板展开 (键序稳定, 防错位) ──
        val positional = mutableListOf<String>()
        val flags = mutableListOf<String>()
        for (param in template) {
            val value = call.parameters[param.key]
            when {
                value == null -> return GateResult(
                    commandLine = "${call.name} ${call.parameters.values.joinToString(" ")}",
                    error = "参数格式错误: 命令 '${call.name}' 缺少参数 \"${param.key}\"。期望键: ${templateKeys(template)}。\n" +
                        "示例: ${jsonExample(call.name)}",
                    errorCode = ErrorCodes.PARAM_FORMAT_ERROR
                )
                param.kind == Kind.FLAG -> if (value == "true") flags.add("--${param.key}")
                else -> positional.add(quoteIfNeeded(value))
            }
        }
        return GateResult(commandLine = buildString {
            append(call.name)
            positional.forEach { append(' ').append(it) }
            flags.forEach { append(' ').append(it) }
        }, reason = reason)
    }

    /**
     * 参数保护 (P4 修复, 2026-08-08 自检): 值含空白(含换行)/引号/反斜杠时用双引号包裹并转义。
     *
     * 否则多行 content 经 HighRiskCommandGate 展开后, CliInterpreter.tokenize 会把真实换行
     * 当空白切分成多个参数 (writeFile 再 joinToString 还原为空格 → 换行丢失), 或把 `\n` 字面
     * 当转义符吃掉反斜杠 (`\n` → `n`)。包裹后引号内空白不切分, `\\`/`\"` 可被 tokenize 还原。
     * 无特殊字符的值不加引号 (既有展开行为零变化)。
     */
    private fun quoteIfNeeded(value: String): String {
        val needsQuote = value.any { it.isWhitespace() || it == '"' || it == '\\' }
        if (!needsQuote) return value
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /** 按模板动态生成完整 JSON 示例 (含 reason), 错误反馈内嵌重发指令格式 (自锁先例)。 */
    private fun jsonExample(name: String): String {
        val template = HIGH_RISK[name] ?: return "$name {\"reason\": \"目的\"}"
        val keys = template.map { "\"${it.key}\": \"...\"" } + "\"reason\": \"目的\""
        return "$name ${keys.joinToString(", ", "{", "}")}"
    }

    private fun templateKeys(template: List<Param>): String =
        (template.map { "\"${it.key}\"" } + "\"reason\"").joinToString(", ")
}
