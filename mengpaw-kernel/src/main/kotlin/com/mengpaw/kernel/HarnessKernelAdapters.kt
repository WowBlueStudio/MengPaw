// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.harness.ConfirmDecision
import com.mengpaw.kernel.harness.HarnessConfirmGate
import com.mengpaw.kernel.harness.HarnessLogger
import com.mengpaw.kernel.harness.HarnessToolInvoker
import com.mengpaw.kernel.harness.HarnessToolRequest
import com.mengpaw.kernel.harness.HarnessToolResult

/**
 * Harness 抽象层 ↔ kernel 既有全局单例的适配器 (A 阶段, 2026-08-21)。
 *
 * 存在意义: 抽象层要求"注入", 而 kernel 现有实现是全局单例。改造期两者必须共存 —
 * 适配器把单例包成接口实现, 使「未显式注入」的路径行为与改造前**逐字等价**,
 * 既有 663 个内核测试无需重写。
 *
 * 边界: 本文件属 MengPaw 壳层配套 (kernel 内), **不搬入 harness 独立仓库** —
 * 独立仓库只保留纯接口 + 无平台默认实现, 由宿主各自提供适配器。
 */

/** 日志桥 — harness 抽象出口转发到 kernel 全局 [KernelLog] (保留 Android 日志适配器注入点)。 */
object KernelLogBridge : HarnessLogger {
    override fun d(tag: String, msg: String) = KernelLog.d(tag, msg)
    override fun w(tag: String, msg: String) = KernelLog.w(tag, msg)
    override fun i(tag: String, msg: String) = KernelLog.i(tag, msg)
    override fun e(tag: String, msg: String) = KernelLog.e(tag, msg)
}

/**
 * 确认门桥 — 转发到既有 [com.mengpaw.kernel.security.UserConfirmBus]。
 *
 * 语义映射限制 (重要): `UserConfirmBus.request` 返回 `Boolean`, **无法区分**
 * 「用户拒绝」「无监听器」「超时」三种情形 — 内部实现已把这三种都折叠为 false。
 * 因此本桥在 false 时返回 [ConfirmDecision.DENIED] (保守: 按"被拒"计, 不谎称有人问过)。
 * 这是过渡期如实降级, 不是接口设计缺陷 — 独立仓库的宿主实现可直接给出精确决策。
 */
object KernelConfirmGate : HarnessConfirmGate {
    override suspend fun request(
        command: String,
        reason: String?,
        riskLabel: String,
        timeoutMs: Long
    ): ConfirmDecision = try {
        val allowed = com.mengpaw.kernel.security.UserConfirmBus.request(
            command = command,
            reason = reason,
            riskLabel = riskLabel,
            timeoutMs = timeoutMs
        )
        if (allowed) ConfirmDecision.ALLOWED else ConfirmDecision.DENIED
    } catch (_: Exception) {
        // fail-closed: 任何异常一律不予执行
        ConfirmDecision.DENIED
    }
}

/**
 * 工具执行器桥 — 「工具即 CLI 命令」语义的既有实现 (MengPaw 单机壳默认)。
 *
 * ReAct 循环里模型输出的 Action 在此还原为一条 CLI 命令文本, 交给既有
 * [PipelineManager] 构建的管线执行 (解析 → 限流 → 安全门 → 完整性 → 执行 → 审计)。
 * 行为与改造前 [com.mengpaw.kernel.AgentToolRunner] 直接调 Pipeline 等价 —
 * 差别仅在于"经由 [HarnessToolInvoker] 接口"这一层间接, 从而允许宿主替换。
 *
 * 参数拼装规则 (与旧路径一致):
 * - [HarnessToolRequest.RAW_KEY] 命中时, 整行原样作为参数尾串 (参数纯净规则);
 * - 其余键按 `--key value` 追加; 空值键退化为裸 `--key`。
 * 位置参数 ([HarnessToolRequest.args]) 优先内联在命令名之后 — 供原生 tool-calling 宿主使用。
 */
class CliPipelineToolInvoker(private val pipelineManager: PipelineManager) : HarnessToolInvoker {

    override suspend fun invoke(request: HarnessToolRequest): HarnessToolResult {
        val commandLine = buildCommandLine(request)
        val context = ExecutionContext(
            sessionId = request.sessionId.ifBlank { "tool" },
            agentName = request.agentName.ifBlank { "agent" }
        )
        val result = try {
            pipelineManager.buildPipeline().execute(commandLine, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return HarnessToolResult.fail(
                "命令执行异常: ${e.message?.take(200) ?: e::class.simpleName ?: "unknown"}",
                errorCode = "INVOKER_EXCEPTION"
            )
        }
        return if (result.success) {
            HarnessToolResult.ok(result.output)
        } else {
            HarnessToolResult.fail(
                result.error ?: result.output,
                errorCode = result.errorCode
            )
        }
    }

    /** 命令名 + 位置参数 + flags 还原为单行命令文本。 */
    private fun buildCommandLine(request: HarnessToolRequest): String = buildString {
        append(request.name)
        request.args.filter { it.isNotBlank() }.forEach { append(' ').append(it) }
        request.flags.forEach { (key, value) ->
            if (key == HarnessToolRequest.RAW_KEY) {
                if (value.isNotBlank()) append(' ').append(value)
            } else {
                append(" --").append(key)
                if (value.isNotBlank()) append(' ').append(value)
            }
        }
    }
}

