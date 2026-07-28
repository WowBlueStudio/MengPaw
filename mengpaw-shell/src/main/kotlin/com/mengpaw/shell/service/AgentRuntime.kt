// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.service

import com.mengpaw.kernel.trigger.TriggerEngine
import com.mengpaw.shell.ui.screens.AgentViewModel

/**
 * Background runtime — wires system events to Agent.
 *
 * Agent initialization follows a simple user-driven flow:
 * 1. Install → workspace files created (bootstrap)
 * 2. User configures API in Settings → applyConfiguration (light)
 * 3. User sends first message → Agent responds via real LLM
 */
object AgentRuntime {

    /** Wire TriggerEngine → AgentViewModel. Called once at app startup. */
    fun wireTriggers(vm: AgentViewModel) {
        // Use a weak reference to prevent the static onFire callback from
        // leaking the ViewModel — TriggerEngine is a JVM singleton.
        val ref = java.lang.ref.WeakReference(vm)
        TriggerEngine.onFire = { trigger -> ref.get()?.submitTriggerTask(trigger) }
        TriggerEngine.start()
    }

    /** Clear the trigger callback. Call when the host ViewModel is being destroyed. */
    fun unwireTriggers() {
        TriggerEngine.onFire = null
    }
}
