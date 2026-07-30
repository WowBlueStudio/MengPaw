// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.InputTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages active input tags (slash commands + @mentions) and loop mode.
 */
class InputTagManager {

    private val _activeTags = MutableStateFlow<List<InputTag>>(emptyList())
    val activeTags: StateFlow<List<InputTag>> = _activeTags.asStateFlow()

    /** Current loop mode — read by submitTask() to choose engine method. */
    var loopMode: LoopMode = LoopMode.REACT

    /** 添加标签，同类型替换旧值。 */
    fun addTag(tag: InputTag) {
        val current = _activeTags.value.toMutableList()
        when (tag) {
            is InputTag.Mode -> {
                current.removeAll { it is InputTag.Mode }
                when (tag.mode) {
                    ExecutionMode.MISSION -> loopMode = LoopMode.MISSION
                    ExecutionMode.GOAL -> loopMode = LoopMode.GOAL
                    ExecutionMode.PLAN -> {} // Plan uses explicit dispatch, doesn't change loopMode
                    else -> {} // RESEARCH/TRANSLATE/SILENT 不改变 loopMode
                }
            }
            is InputTag.AgentRef -> {
                current.removeAll { it is InputTag.AgentRef && it.agentName == tag.agentName }
            }
        }
        current.add(tag)
        _activeTags.value = current
    }

    /** 移除标签，模式标签移除时回退到 REACT。 */
    fun removeTag(tag: InputTag) {
        _activeTags.value = _activeTags.value.filter { it != tag }
        if (tag is InputTag.Mode && _activeTags.value.none { it is InputTag.Mode }) {
            loopMode = LoopMode.REACT
        }
    }

    /** 清除所有标签。 */
    fun clearTags() {
        _activeTags.value = emptyList()
        loopMode = LoopMode.REACT
    }

    /** 获取可用于 @mention 的 Agent 列表（本地 + 框架）。 */
    fun agentNamesForMention(sessions: Map<String, AgentSession>): List<Pair<String, String?>> {
        return sessions.keys.map { it to sessions[it]?.framework }
    }
}
