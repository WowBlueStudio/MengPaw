// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core

import android.content.Context
import com.mengpaw.kernel.DataPaths

/**
 * Android bridge: initializes kernel's DataPaths with the app's files directory.
 * Call in Application.onCreate() or MainActivity.onCreate() before any kernel operations.
 */
object DataPathsInitializer {
    fun initialize(context: Context) {
        DataPaths.initialize(context.filesDir.absolutePath)
    }
}
