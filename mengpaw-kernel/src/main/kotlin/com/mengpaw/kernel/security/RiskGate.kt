// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.harness.ConfirmDecision
import com.mengpaw.kernel.harness.HarnessConfirmGate

/**
 * 分级拦截求值 (v0.34.3) — 主循环 / Swarm worker 共用同一纯函数。
 *
 * LOW → 放行; MID → STANDARD 权限拒绝 (TRUSTED 放行); HIGH → 弹窗确认
 * (allowUserConfirm=false 的 worker 环境一律拒绝)。
 *
 * **A 阶段 (2026-08-21)**: 高危确认改经 [HarnessConfirmGate] 抽象 —
 * 宿主可注入自己的确认形态 (终端 y/n / 对话框 / HTTP 回调)。
 * [confirmGate] 为 null 时回落既有 [UserConfirmBus] 单例, 行为与改造前逐字等价。
 */
object RiskGate {

    /**
     * 求值分级拦截。
     * @param gate HighRiskCommandGate 求值结果 (含 reason)
     * @param agent 执行 Agent 名 (查权限等级)
     * @param allowUserConfirm 主循环 true (可弹窗); worker false (高危直接拒绝)
     * @param confirmGate 平台确认门 (A 阶段抽象); null = 回落 UserConfirmBus 单例
     * @return 错误文本 (应拒绝执行) 或 null (放行)
     */
    suspend fun evaluate(
        gate: HighRiskCommandGate.GateResult,
        agent: String,
        allowUserConfirm: Boolean,
        confirmGate: HarnessConfirmGate? = null
    ): String? {
        val cmdName = gate.commandLine.trim().split(" ").firstOrNull() ?: return null
        return when (CommandRiskLevels.levelOf(cmdName)) {
            RiskLevel.LOW -> {
                // v0.36.x 去重: agent.write/mkdir 已移除 (Linux 命令等价);
                // Linux 重定向写目标由 CommandMonitor 的 overwrite-system 规则 + Android 权限约束兜底
                null
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
                val deniedMessage = "用户拒绝了高危操作: $cmdName"
                if (confirmGate != null) {
                    val decision = confirmGate.request(
                        command = cmdName,
                        reason = gate.reason,
                        riskLabel = RiskLevel.HIGH.label
                    )
                    // 安全默认: 无用户可问 (NO_LISTENER) / 超时 (TIMEOUT) 与拒等同 — 均不执行
                    if (decision.isAllowed) null else deniedMessage
                } else {
                    val allowed = UserConfirmBus.request(
                        command = cmdName,
                        reason = gate.reason,
                        riskLabel = RiskLevel.HIGH.label
                    )
                    if (allowed) null else deniedMessage
                }
            }
        }
    }

}
