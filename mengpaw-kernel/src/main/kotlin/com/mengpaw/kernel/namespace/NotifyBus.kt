// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.namespace

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Agent→User push notification bus.
 *
 * Agent can proactively push messages/banners to the user via CLI commands.
 * The shell UI observes these flows and renders them inline.
 *
 * Usage (Agent CLI):
 *   notify.message 任务已完成，共处理 3 个文件
 *   notify.banner 发现安全风险 --level warn
 */
object NotifyBus {

    /** A notification event pushed by the Agent. */
    data class NotifyEvent(
        val type: NotifyType,
        val text: String,
        val level: NotifyLevel = NotifyLevel.INFO,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class NotifyType { MESSAGE, BANNER }
    enum class NotifyLevel { INFO, SUCCESS, WARN, ERROR }

    private val _events = MutableSharedFlow<NotifyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NotifyEvent> = _events.asSharedFlow()

    /** Push a chat message visible to the user. */
    fun message(text: String) {
        _events.tryEmit(NotifyEvent(NotifyType.MESSAGE, text))
    }

    /** Push a banner notification overlay. */
    fun banner(text: String, level: NotifyLevel = NotifyLevel.INFO) {
        _events.tryEmit(NotifyEvent(NotifyType.BANNER, text, level))
    }
}
