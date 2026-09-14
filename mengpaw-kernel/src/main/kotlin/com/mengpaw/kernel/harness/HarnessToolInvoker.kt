// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.harness

/**
 * Harness 平台抽象 — 工具执行器 (A 阶段, 2026-08-21)。
 *
 * 存在意义: ReAct 循环的"Action"最终要落到某个执行体上。MengPaw 现有实现是
 * 「工具即 CLI 命令」— 经 [com.mengpaw.kernel.cli.Pipeline] 执行 (解析 → 限流 →
 * 安全门 → 完整性 → 执行 → 审计)。这对 Android 单机壳是对的, 但作为跨平台
 * Harness 核心, 宿主可能希望接入: 原生函数调用 (OpenAI tools)、MCP 远程工具、
 * 沙箱进程、甚至纯内存桩 (测试)。
 *
 * 本接口把「模型想做什么」与「宿主怎么做」解耦: harness 核心只在循环里调用
 * [invoke], 具体路由由宿主决定。MengPaw 壳注入的默认实现仍走原 CLI Pipeline,
 * 行为零变化。
 *
 * 契约:
 * - **不得抛异常** — 执行失败必须以 [HarnessToolResult.success] = false 表达,
 *   否则会把工具错误升级成整个循环崩溃 (现有 CLI 语义即如此)。
 * - 实现方负责自身的超时/限流/安全分级; harness 核心不重复做。
 * - 线程安全: 单批工具调用会被 harness 并行发起 (最多 8 路)。
 */
interface HarnessToolInvoker {

    /**
     * 执行一次工具调用。
     *
     * @param request 工具名 + 参数 + 会话上下文
     * @return 执行结果 (成功/失败 + 观察文本)。失败返回, 不抛异常。
     */
    suspend fun invoke(request: HarnessToolRequest): HarnessToolResult

    /**
     * 当前可用的工具名列表 — 供系统提示词渲染「可用工具」段落。
     * 返回空列表表示宿主不提供工具发现 (提示词将由宿主自行注入)。
     */
    fun listTools(): List<HarnessToolSpec> = emptyList()
}

/**
 * 一次工具调用请求。
 *
 * @param name 工具名 (MengPaw 中即 CLI 命令名, 如 `fs.cat`)
 * @param args 位置参数 (已按宿主解析规则拆分)
 * @param flags 显式 flag 参数 (`--key value` 形态)
 * @param sessionId 发起调用的会话 id — 供审计与并发隔离
 * @param agentName 发起调用的 Agent 名 — 供权限分级
 */
data class HarnessToolRequest(
    val name: String,
    val args: List<String> = emptyList(),
    val flags: Map<String, String> = emptyMap(),
    val sessionId: String = "",
    val agentName: String = ""
) {
    /**
     * 原始参数行 — 与旧 `ToolCall.parameters["raw"]` 语义对齐:
     * 参数纯净规则下, 模型给出的整行文本原样透传, 由执行方自行解释。
     */
    val raw: String? get() = flags[RAW_KEY]

    companion object {
        /** 参数整行透传的保留键 (与 kernel ToolCall 的 raw 兜底键一致)。 */
        const val RAW_KEY = "raw"

        /** 便捷构造 — 单个 raw 参数 (最常见形态)。 */
        fun ofRaw(name: String, raw: String, sessionId: String = "", agentName: String = ""): HarnessToolRequest =
            HarnessToolRequest(name, args = emptyList(), flags = mapOf(RAW_KEY to raw), sessionId = sessionId, agentName = agentName)
    }
}

/**
 * 工具执行结果。
 *
 * @param success 是否成功 — 决定 harness 是否将其计入循环的失败统计
 * @param output 观察文本 (Observation), 原样回灌给模型
 * @param errorCode 失败时的机器可读错误码 (宿主自定义; 供重试策略判定)
 */
data class HarnessToolResult(
    val success: Boolean,
    val output: String,
    val errorCode: String? = null
) {
    companion object {
        fun ok(output: String): HarnessToolResult = HarnessToolResult(true, output)
        fun fail(output: String, errorCode: String? = null): HarnessToolResult =
            HarnessToolResult(false, output, errorCode)
    }
}

/**
 * 工具规格 — 供系统提示词向模型描述可用工具。
 *
 * @param name 工具名 (含命名空间, 如 `fs.cat`)
 * @param description 一句话功能描述 (会被注入提示词, 务必简短)
 * @param signature 用法签名 (如 `fs.cat <路径>`), 可为空
 * @param riskLevel 风险分级标签 (`low`/`mid`/`high`), 供提示词警示与宿主门禁
 */
data class HarnessToolSpec(
    val name: String,
    val description: String = "",
    val signature: String = "",
    val riskLevel: String = "low"
)
