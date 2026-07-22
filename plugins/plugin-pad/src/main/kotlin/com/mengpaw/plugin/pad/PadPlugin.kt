// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.pad

import android.content.Context
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * PAD 悬浮窗插件 — 已移除。
 *
 * FloatingDotService 在 Android 14+ 荣耀 MagicOS 上因 SYSTEM_ALERT_WINDOW
 * 权限问题反复崩溃。该功能非核心，移除后不影响 Agent 正常运行。
 *
 * 保留 Plugin 壳以维持兼容性（已安装用户升级时不会报缺失）。
 */
class PadPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "pad-plugin", name = "PAD 悬浮窗", version = "0.1.2",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "已移除 — 悬浮窗功能因系统权限兼容性问题暂时下线",
        permissions = emptyList(),
        minCoreVersion = "0.2.0",
        commands = listOf("pad.show", "pad.hide")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "show" to { _, _ ->
            com.mengpaw.kernel.cli.ExecutionResult.ok("PAD 悬浮窗功能已移除")
        },
        "hide" to { _, _ ->
            com.mengpaw.kernel.cli.ExecutionResult.ok("PAD 悬浮窗功能已移除")
        }
    )

    override suspend fun onInstall(context: PluginContext) {}

    companion object {
        /** No-op — kept for binary compatibility. */
        @Deprecated("FloatingDotService removed in v0.7.6", ReplaceWith(""))
        fun init(context: Context) {}

        /** No-op — kept for binary compatibility. */
        @Deprecated("FloatingDotService removed in v0.7.6", ReplaceWith(""))
        fun updateState(state: DotState) {}

        /** No-op — kept for binary compatibility. */
        @Deprecated("FloatingDotService removed in v0.7.6", ReplaceWith(""))
        fun show() {}

        /** No-op — kept for binary compatibility. */
        @Deprecated("FloatingDotService removed in v0.7.6", ReplaceWith(""))
        fun hide() {}

        /** No-op — kept for binary compatibility. */
        @Deprecated("FloatingDotService removed in v0.7.6", ReplaceWith(""))
        fun isVisible(): Boolean = false
    }

    @Deprecated("FloatingDotService removed in v0.7.6")
    enum class DotState { IDLE, WORKING, ERROR }
}
