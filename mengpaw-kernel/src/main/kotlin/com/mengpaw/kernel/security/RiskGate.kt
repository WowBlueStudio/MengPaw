// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

/**
 * 分级拦截求值 (v0.34.3) — 主循环 / Swarm worker / Mission worker 共用同一纯函数。
 *
 * LOW → 放行; MID → STANDARD 权限拒绝 (TRUSTED 放行); HIGH → 弹窗确认
 * (allowUserConfirm=false 的 worker 环境一律拒绝)。
 */
object RiskGate {

    /**
     * 求值分级拦截。
     * @param gate HighRiskCommandGate 求值结果 (含 reason)
     * @param agent 执行 Agent 名 (查权限等级)
     * @param allowUserConfirm 主循环 true (可弹窗); worker false (高危直接拒绝)
     * @return 错误文本 (应拒绝执行) 或 null (放行)
     */
    suspend fun evaluate(
        gate: HighRiskCommandGate.GateResult,
        agent: String,
        allowUserConfirm: Boolean
    ): String? {
        val cmdName = gate.commandLine.trim().split(" ").firstOrNull() ?: return null
        return when (CommandRiskLevels.levelOf(cmdName)) {
            RiskLevel.LOW -> {
                // v0.34.3 P0-2 ④ (铲子检测): agent.write/mkdir 写入工作区/输出目录外
                // (如 /sdcard 任意路径) → 降级中危, 默认拒绝, TRUSTED 放行。
                // 防第三方模型服务端诱导 Agent 在任意位置落盘恶意文件。
                if ((cmdName == "agent.write" || cmdName == "agent.mkdir") &&
                    !isAllowedWriteTarget(gate.commandLine)
                ) {
                    if (AgentPermissionStore.levelOf(agent) == AgentPermissionLevel.TRUSTED) null
                    else "命令 '$cmdName' 写入工作区/输出目录之外的路径，属于中危操作，当前 Agent 权限不足。" +
                        "\n允许范围: 工作区 (Agent文档)、输出目录 (agent.output)、录音/截图存档。提升权限: 智能体设置 → 权限等级 → 信任。"
                } else null
            }
            RiskLevel.MID -> {
                if (AgentPermissionStore.levelOf(agent) == AgentPermissionLevel.TRUSTED) null
                else "命令 '$cmdName' 属于中危操作（删除/修改/隐私读取），当前 Agent 权限不足。" +
                    "\n提升权限: 智能体设置 → 权限等级 → 信任。"
            }
            RiskLevel.HIGH -> {
                if (!allowUserConfirm) {
                    return "命令 '$cmdName' 属于高危操作，当前执行环境（worker/后台）不弹窗确认，已阻止。"
                }
                val allowed = UserConfirmBus.request(
                    command = cmdName,
                    reason = gate.reason,
                    riskLabel = RiskLevel.HIGH.label
                )
                if (allowed) null else "用户拒绝了高危操作: $cmdName"
            }
        }
    }

    /** 写目标是否在允许区 — 相对路径 (工作区基准) / 工作区 / 输出目录 / 录音 / 截图存档。 */
    private fun isAllowedWriteTarget(commandLine: String): Boolean {
        val raw = commandLine.trim().split(Regex("\\s+")).drop(1).firstOrNull()?.trim('"') ?: return true
        if (!raw.startsWith("/")) return true // 相对路径以工作区为基准
        return raw.startsWith(com.mengpaw.kernel.DataPaths.AGENTS) ||
            raw.startsWith(com.mengpaw.kernel.DataPaths.OUTPUT) ||
            raw.startsWith(com.mengpaw.kernel.DataPaths.RECORDINGS) ||
            raw.startsWith(com.mengpaw.kernel.DataPaths.SCREENSHOTS)
    }
}
