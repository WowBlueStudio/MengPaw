// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.harness

import com.mengpaw.kernel.KernelConfirmGate
import com.mengpaw.kernel.KernelLogBridge

/**
 * Harness 平台环境聚合 (A 阶段, 2026-08-21)。
 *
 * 这是 ReAct 核心向宿主索取的**全部**平台能力清单 — 除此之外 harness 核心不直接接触
 * 任何平台 API。宿主在启动时构造一个实例并注入引擎, 之后所有路径/IO/时间/确认
 * 都经此路由。
 *
 * 为什么是聚合而非散注入: 引擎构造参数已多达 11 个, 逐个追加会把构造器变成
 * 参数垃圾场; 聚合后新增平台能力不破坏既有构造签名。
 *
 * 不含 [HarnessToolInvoker] — 工具执行属**领域能力**而非平台能力: 它承载的是
 * "工具是什么"的产品决策 (CLI / function-calling / MCP), 不是"平台提供了什么"。
 * 工具执行经引擎的独立构造参数注入, 保持两轴解耦。
 *
 * @param fileSystem 文件系统
 * @param paths 逻辑路径解析
 * @param clock 时间源
 * @param logger 日志出口
 * @param confirmGate 高危确认门; 无 UI 宿主应传 [DenyAllConfirmGate] (一律拒绝)
 */
data class HarnessEnv(
    val fileSystem: HarnessFileSystem,
    val paths: HarnessPathResolver,
    val clock: HarnessClock = JvmHarnessClock,
    val logger: HarnessLogger = ConsoleHarnessLogger,
    val confirmGate: HarnessConfirmGate = DenyAllConfirmGate
) {
    companion object {
        /**
         * 由既有全局单例组装 (改造期过渡用) — 保持 kernel 现有行为逐字不变。
         *
         * 定位: ReAct 主链路从全局单例切到注入时, 默认值取本工厂, 于是
         * 「未显式注入」与「改造前行为」完全等价, 既有测试无需重写。
         * 全部迁移完成后本工厂下沉到 MengPaw 壳层, harness 核心不再认识全局单例。
         */
        fun fromKernelGlobals(
            fileSystem: HarnessFileSystem = JvmHarnessFileSystem,
            confirmGate: HarnessConfirmGate = KernelConfirmGate
        ): HarnessEnv = HarnessEnv(
            fileSystem = fileSystem,
            paths = com.mengpaw.kernel.DataPaths.resolver,
            clock = JvmHarnessClock,
            logger = KernelLogBridge,
            confirmGate = confirmGate
        )
    }
}

/**
 * Harness 日志出口 — 与 kernel 既有 [com.mengpaw.kernel.Logger] 同形, 但**不反向依赖**
 * kernel 根包: harness 核心搬到独立仓库后, host 只需实现本接口。
 */
interface HarnessLogger {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun e(tag: String, msg: String)
}

/** 控制台日志 — JVM/桌面/CLI 默认, 零依赖。 */
object ConsoleHarnessLogger : HarnessLogger {
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")
    override fun w(tag: String, msg: String) = println("W/$tag: $msg")
    override fun i(tag: String, msg: String) = println("I/$tag: $msg")
    override fun e(tag: String, msg: String) = println("E/$tag: $msg")
}

/**
 * 默认确认门 — **一律拒绝** (fail-closed)。
 * 无 UI 宿主 (后台 worker / 无人值守 CLI / 测试) 必须使用本实现, 不得静默放行高危操作。
 */
object DenyAllConfirmGate : HarnessConfirmGate {
    override suspend fun request(
        command: String,
        reason: String?,
        riskLabel: String,
        timeoutMs: Long
    ): ConfirmDecision = ConfirmDecision.NO_LISTENER
}
