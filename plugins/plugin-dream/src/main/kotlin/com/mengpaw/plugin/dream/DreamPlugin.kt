// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.dream

import com.mengpaw.kernel.agent.DreamEngine
import com.mengpaw.kernel.agent.DreamProvider
import com.mengpaw.kernel.agent.DreamProviderRegistry
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * 梦境模式 — 内置默认实现 (不可移除)。
 *
 * ⚠️ **内置插件, 不能直接移除**: 梦境是记忆管道的核心环节, 随 Shell APK 编译分发,
 * 无独立卸载通道 (PluginExecutor.UNINSTALLABLE 白名单锁定, plugin.disable 可临时禁用)。
 *
 * 可替换性: 第三方插件可实现内核 [DreamProvider] SPI, 在 onInstall 时注册自己的实现
 * (后注册者胜) — 输入组装 / LLM 提炼 / 文件整理可整体定制, 无需 fork 本插件。
 * 卸载第三方提供者后自动回退内核默认 (DreamEngine)。
 */
class DreamPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "dream-plugin",
        name = "梦境模式",
        version = "", // 内置插件, 随 Shell APK 版本更新
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "梦境模式内置默认实现 — 记忆整理管道 (读→备份→{date}_dream.md→到期删除)。不可移除; 第三方可实现 DreamProvider 覆盖",
        minCoreVersion = "0.2.0",
        commands = emptyList() // agent.dream 命令在内核命名空间, 本插件只注册提供者
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = emptyMap()
    override val uiButtons: List<com.mengpaw.kernel.plugin.PluginUiButton> = emptyList()

    override suspend fun onInstall(ctx: PluginContext) {
        DreamProviderRegistry.register(DreamEngine)
        ctx.log("梦境模式默认实现已注册 (DreamProvider SPI — 第三方可实现接口覆盖)")
    }

    override suspend fun onUninstall() {
        DreamProviderRegistry.unregister(DreamEngine.providerName)
    }

    override suspend fun onUpgrade(newVersion: String) {}
}
