// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.LinuxCommandExecutor
import com.mengpaw.kernel.harness.HarnessToolRequest
import com.mengpaw.kernel.security.HighRiskCommandGate
import com.mengpaw.kernel.security.RiskGate
import com.mengpaw.kernel.security.SourceBlocklist
import kotlinx.coroutines.withTimeout

/**
 * 工具执行器 (v0.34.3 抽自并行执行分支, v0.40.4 P2 拆自 AgentReActLoop 400 行红线)。
 * 分级拦截 + 来源黑名单 + 执行; 主循环可弹窗确认。
 *
 * - MID 权限不足 → ERR_PERMISSION_DENIED; HIGH → UserConfirmBus 弹窗, 拒绝即阻挡;
 * - 黑名单来源 → ERR_SOURCE_BLOCKED; 全过 → 60s 超时执行。
 * - Linux 命令通道: 注册表未命中的命令回退到沙箱 shell (与 bang 同一套监控),
 *   严格限定"真命令不存在" (Unknown command) 才落 shell 兜底。
 *
 * **A 阶段改造 (2026-08-21)**: 注册表内命令的执行由直连 Pipeline 改为经
 * [com.mengpaw.kernel.harness.HarnessToolInvoker] 抽象 — MengPaw 壳注入的默认实现
 * ([CliPipelineToolInvoker]) 仍走原管线, 行为不变; 跨平台宿主可替换执行形态
 * (function-calling / MCP / 沙箱进程)。Linux shell 兜底属宿主特定能力, 不进抽象层。
 */
internal suspend fun runRiskGuarded(
    engine: AgentEngine,
    gate: HighRiskCommandGate.GateResult,
    agent: String,
    context: ExecutionContext
): ExecutionResult {
    val riskError = RiskGate.evaluate(gate, agent, allowUserConfirm = true, confirmGate = engine.harnessEnv.confirmGate)
    if (riskError != null) {
        return ExecutionResult.fail(riskError, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
    }
    val source = SourceBlocklist.extractSource(gate.commandLine)
    if (source != null && SourceBlocklist.isBlocked(source)) {
        return ExecutionResult.fail(
            "来源已在黑名单，工具结果已阻止。security.blocklist 查看黑名单。",
            errorCode = ErrorCodes.ERR_SOURCE_BLOCKED)
    }
    val result = withTimeout(60_000L) {
        invokeThroughHarness(engine, gate.commandLine, context)
    }
    // Linux 命令通道: 命令存在但参数错误 (ERR_NOT_FOUND) 不得落 shell 兜底,
    // 否则掩盖真实错误 (错误码二义性修复)
    if (!result.success && result.errorCode == ErrorCodes.ERR_NOT_FOUND &&
        result.error?.startsWith("Unknown command") == true
    ) {
        return LinuxCommandExecutor.execute(gate.commandLine, context, allowUserConfirm = true)
    }
    return result
}

/**
 * 把一条命令文本经 [com.mengpaw.kernel.harness.HarnessToolInvoker] 执行, 并还原为
 * kernel 内部 [ExecutionResult]。
 *
 * 还原约定: 命令名后的整段参数按"参数纯净规则"作为单条 raw 参数传递 (与旧路径把整行
 * 交给 Pipeline 解析等价); 失败时 [com.mengpaw.kernel.harness.HarnessToolResult.output]
 * 承载错误正文, 使上层 `Unknown command` 判定与错误码语义得以保留。
 */
private suspend fun invokeThroughHarness(
    engine: AgentEngine,
    commandLine: String,
    context: ExecutionContext
): ExecutionResult {
    val name = commandLine.trim().split(" ").firstOrNull().orEmpty()
    val rawArgs = commandLine.trim().removePrefix(name).trim()
    val request = HarnessToolRequest(
        name = name,
        args = emptyList(),
        flags = if (rawArgs.isEmpty()) emptyMap() else mapOf(HarnessToolRequest.RAW_KEY to rawArgs),
        sessionId = context.sessionId,
        agentName = context.agentName.orEmpty()
    )
    val toolResult = engine.harnessToolInvoker.invoke(request)
    return if (toolResult.success) {
        ExecutionResult.ok(toolResult.output)
    } else {
        ExecutionResult.fail(
            toolResult.output.ifBlank { "(工具执行失败, 无错误详情)" },
            errorCode = toolResult.errorCode ?: ErrorCodes.ERR_INVALID_INPUT
        )
    }
}
