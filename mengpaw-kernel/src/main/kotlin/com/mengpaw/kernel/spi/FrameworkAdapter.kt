// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.spi

/**
 * 框架连接器 SPI — 让非 MengPaw 框架 (OpenClaw/QwenPaw/Claude Code 等) 接入 MengPaw。
 *
 * 协议分层:
 * - 内核 = 协议核心 (ACP 消息/信任 + MCP JSON-RPC), 不含任何具体框架
 * - 连接器插件 (外部分发) 实现本接口, 经 [FrameworkAdapterRegistry] 注册
 * - plugin-framework (内置协议插件) 的 `framework.connect/call` 按通讯录类型分派到对应适配器
 *
 * 连接器插件只依赖内核 — 实现本接口 + onInstall 时注册即可, 零框架耦合。
 */
interface FrameworkAdapter {

    /** 框架类型名 — 与 FrameworkPeerStore.FRAMEWORK_TYPES 的键一致 (如 "openclaw"/"qwenpaw")。 */
    val frameworkName: String

    /** 建立到目标框架实例的连接。 */
    suspend fun connect(target: FrameworkTarget): Result<Unit>

    /** 断开连接。 */
    suspend fun disconnect()

    /** 调用远端框架的工具/能力, 返回文本结果。 */
    suspend fun callTool(tool: String, args: Map<String, String>): Result<String>

    /** 当前是否在线 (已连接且心跳存活)。 */
    fun isOnline(): Boolean
}

/** 连接目标 — 通讯录条目解析出的最小信息 (内核定义, 不依赖 plugin-framework 类型)。 */
data class FrameworkTarget(
    val name: String,
    val type: String,
    val address: String,
    val port: Int
)

/**
 * 框架连接器注册表 (内核持有) — 连接器插件 onInstall 时 register, plugin-framework 查询分派。
 */
object FrameworkAdapterRegistry {
    private val adapters = LinkedHashMap<String, FrameworkAdapter>()

    @Synchronized
    fun register(adapter: FrameworkAdapter) { adapters[adapter.frameworkName] = adapter }

    @Synchronized
    fun unregister(frameworkName: String) { adapters.remove(frameworkName) }

    @Synchronized
    fun find(frameworkName: String): FrameworkAdapter? = adapters[frameworkName]

    @Synchronized
    fun list(): List<FrameworkAdapter> = adapters.values.toList()
}
