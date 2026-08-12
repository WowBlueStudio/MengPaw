// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * 通知监听服务 — sys.notification.list 的数据源 (对齐 Termux:API termux-notification-list)。
 *
 * 需用户在系统『通知使用权』手动开启 (设置 → 通知使用权 → MengPaw), 非 Manifest 运行时权限。
 * 服务运行期间缓存可见通知 (最新覆盖同 key), Agent 经 [snapshot] 读取。
 */
internal class MengPawNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        put(sbn.key, format(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        remove(sbn.key)
    }

    override fun onListenerConnected() {
        try {
            activeNotifications.forEach { put(it.key, format(it)) }
        } catch (_: Exception) {}
    }

    private fun format(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return "[${sbn.packageName}] $title: $text"
    }

    companion object {
        private val cacheLock = Any()
        private val cache = LinkedHashMap<String, String>()

        fun snapshot(): List<String> = synchronized(cacheLock) { cache.values.toList() }

        fun isAuthorized(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        private fun put(key: String, value: String) {
            synchronized(cacheLock) { cache[key] = value }
        }

        private fun remove(key: String) {
            synchronized(cacheLock) { cache.remove(key) }
        }
    }
}
