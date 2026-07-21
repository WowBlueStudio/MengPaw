// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

/**
 * A named block of system prompt text. Plugins and host modules
 * register sections at well-known anchors; the builder stitches them
 * in a deterministic order.
 *
 * Inspired by QwenPaw's PromptBuilder (Apache 2.0), adapted for Kotlin.
 */
data class PromptSection(val anchor: String, val content: String)

/**
 * Anchor-based system prompt assembler.
 * Host sections are emitted first (in anchor order), then plugin sections
 * are inserted after their declared anchor.
 */
class PromptBuilder(hostAnchors: List<String> = DEFAULT_ANCHORS) {

    private val anchors = hostAnchors.toMutableList()
    private val hostSections = mutableMapOf<String, String>()
    private val pluginSections = mutableMapOf<String, MutableList<String>>()

    companion object {
        val DEFAULT_ANCHORS = listOf("identity", "workspace", "rules", "tools", "fewshot", "docs", "memory")
    }

    fun registerAnchor(name: String) { if (name !in anchors) anchors.add(name) }

    fun setHostSection(anchor: String, content: String) { hostSections[anchor] = content }

    fun addPluginSection(anchor: String, content: String) {
        pluginSections.getOrPut(anchor) { mutableListOf() }.add(content)
    }

    fun clearPlugins() { pluginSections.clear() }

    /**
     * Build the final system prompt by stitching sections in anchor order.
     * Empty sections are skipped. Plugin sections follow their host anchor immediately.
     */
    fun build(): String = buildString {
        for (anchor in anchors) {
            hostSections[anchor]?.let { append(it); append("\n\n") }
            pluginSections[anchor]?.forEach { append(it); append("\n\n") }
        }
    }.trimEnd()

    /** Estimate total character count for cache diagnostics. */
    fun totalChars(): Int = build().length
}
