// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * 工作区文档变更监听器 — 拆自 AgentDocs (400 行文件拆分)。
 * PromptEngine 用它失效缓存; shell 用它刷新工作区文件实时列表。
 */
internal class AgentDocsListeners {

    /** Workspace doc change listeners — fired when Agent modifies workspace docs.
     *  PromptEngine uses this to invalidate cache; the shell uses it to refresh
     *  the live workspace file list.  @param agentName 被修改的 Agent 名称
     *  @param filePath 被修改文件的完整路径; null 表示未知 (兼容旧行为, 全量失效) */
    private val docListeners = java.util.concurrent.CopyOnWriteArrayList<(String, String?) -> Unit>()

    /** Register a workspace doc change listener. */
    internal fun addDocListener(listener: (agentName: String, filePath: String?) -> Unit) {
        docListeners.add(listener)
    }

    /** Remove a previously registered workspace doc change listener. */
    internal fun removeDocListener(listener: (agentName: String, filePath: String?) -> Unit) {
        docListeners.remove(listener)
    }

    /** Notify all listeners that an agent workspace document changed. */
    internal fun notifyDocChanged(agentName: String, filePath: String?) {
        docListeners.forEach { listener ->
            try { listener(agentName, filePath) }
            catch (_: Exception) { /* 单监听器失败不阻塞其余 */ }
        }
    }
}
