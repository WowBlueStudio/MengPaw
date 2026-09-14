// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.harness

/**
 * Harness 平台抽象 — 高危操作确认门 (A 阶段, 2026-08-21)。
 *
 * 存在意义: ReAct 主循环在工具执行前必须能"暂停并问用户" (高危分级: `RiskGate`);
 * 现有实现硬绑 [com.mengpaw.kernel.security.UserConfirmBus] 全局单例 + shell 弹窗 +
 * `java.util.concurrent`。跨平台宿主 (CLI/桌面/Web) 的确认形态各不相同 (终端 y/n、
 * 对话框、HTTP 回调), 且**无 UI 的后台 worker 必须默认拒绝** — 这是安全默认, 不可让步。
 *
 * 契约: 实现方必须保证「无用户可问」与「用户拒绝」都被如实区分返回
 * ([ConfirmDecision.NO_LISTENER] vs [ConfirmDecision.DENIED]), 因为二者的审计语义与
 * 对模型的提示措辞不同; 任何异常路径一律视为拒绝 (fail-closed)。
 */
interface HarnessConfirmGate {

    /**
     * 请求用户确认一项高危操作。**必须挂起直到决出或超时**。
     *
     * @param command 待确认的命令名 (非全文, 供弹窗标题)
     * @param reason 模型声明的高危意图 (可为空)
     * @param riskLabel 风险等级标签 (供 UI 着色)
     * @param timeoutMs 超时上限; 超时必须按拒绝收尾
     * @return 决出结果 — 永不抛异常
     */
    suspend fun request(
        command: String,
        reason: String?,
        riskLabel: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ConfirmDecision

    companion object {
        /** 默认确认超时 — 与旧 UserConfirmBus 行为一致 (30s 后默认拒绝)。 */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}

/** 确认门决出结果 — 区分"被拒"与"无人可问", 二者审计语义不同。 */
enum class ConfirmDecision {
    /** 用户明确允许。 */
    ALLOWED,

    /** 用户明确拒绝。 */
    DENIED,

    /** 无任何监听者 (后台 worker / 无 UI 宿主) — 安全默认, 不予执行。 */
    NO_LISTENER,

    /** 超时未决 — 安全默认, 不予执行。 */
    TIMEOUT;

    /** 是否放行执行。 */
    val isAllowed: Boolean get() = this == ALLOWED
}
