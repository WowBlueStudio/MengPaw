// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core

import android.util.Log
import com.mengpaw.kernel.Logger

/**
 * Android Log adapter — injects Android's Log into the kernel's KernelLog system.
 * Call KernelLog.setLogger(AndroidLogger()) at app startup.
 */
class AndroidLogger : Logger {
    override fun d(tag: String, msg: String) { Log.d(tag, msg) }
    override fun w(tag: String, msg: String) { Log.w(tag, msg) }
    override fun i(tag: String, msg: String) { Log.i(tag, msg) }
    override fun e(tag: String, msg: String) { Log.e(tag, msg) }
}
