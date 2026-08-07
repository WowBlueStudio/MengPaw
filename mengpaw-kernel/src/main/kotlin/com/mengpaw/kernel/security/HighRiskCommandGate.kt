// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.llm.ToolCall

/**
 * 高危命令 reason 门禁 (P0 注入防护 v0.34.1)。
 *
 * Agent 自主调用高危命令（文件写/删、proc、插件管理、通知、剪贴板、记忆写、技能开关、
 * root.*）必须附意图声明 (reason)。形态 = **JSON 豁免通道**:
 * 高危命令豁免 [ToolCall.paramFormatError] 全局门卫, 允许结构化 `{"reason": ...}` 参数;
 * 非高危命令维持原门卫 (行为零变化)。
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

    /** 高危命令 → 参数模板。集合按真实注册表校准 (fs.write 已并入 agent.*, 不在此列)。 */
    val HIGH_RISK: Map<String, List<Param>> = mapOf(
        // ── 文件 ──
        "agent.write" to listOf(Param("path"), Param("content")),
        "agent.rm" to listOf(Param("path"), Param("force", Kind.FLAG)),   // 文件需 --force (自锁先例)
        "agent.mkdir" to listOf(Param("path")),
        "fs.mv" to listOf(Param("source"), Param("dest")),
        "fs.cp" to listOf(Param("source"), Param("dest")),
        // ── 进程 ──
        "proc.exec" to listOf(Param("command")),
        "proc.system" to listOf(Param("command")),
        "proc.kill" to listOf(Param("pid")),
        // ── 插件管理 ──
        "plugin.install" to listOf(Param("id")),
        "plugin.uninstall" to listOf(Param("id")),
        "plugin.enable" to listOf(Param("id")),
        "plugin.disable" to listOf(Param("id")),
        "plugin.update" to listOf(Param("id")),
        // ── 通知 ──
        "self.notify.message" to listOf(Param("text")),
        "self.notify.banner" to listOf(Param("text")),
        // ── 剪贴板 ──
        "clipboard.copy" to listOf(Param("text")),
        "clipboard.paste" to emptyList(),
        "clipboard.clear" to emptyList(),
        // ── 技能开关 ──
        "skill.enable" to listOf(Param("id")),
        "skill.disable" to listOf(Param("id")),
        // ── 记忆写/改/删 (record 不入: append-only 日记, 危害低) ──
        "agent.memory.keep" to listOf(Param("content")),
        "agent.memory.write" to listOf(Param("id"), Param("content")),
        "agent.memory.rm" to listOf(Param("timestamp")),
        "agent.memory.edit" to listOf(Param("timestamp"), Param("content")),
        "agent.memory.mid.delete" to listOf(Param("date")),
        "agent.memory.mid.rm" to listOf(Param("date"), Param("timestamp")),
        "agent.memory.mid.edit" to listOf(Param("date"), Param("timestamp"), Param("content")),
        "agent.memory.project.save" to listOf(Param("project"), Param("content")),
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
                else -> positional.add(value)
            }
        }
        return GateResult(commandLine = buildString {
            append(call.name)
            positional.forEach { append(' ').append(it) }
            flags.forEach { append(' ').append(it) }
        }, reason = reason)
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
