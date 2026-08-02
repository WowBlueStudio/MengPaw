// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.concise

import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.kernel.plugin.PluginManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 提示词导出辅助 — 生成真实 LLM 对比用的 before/after 提示词文件。
 *
 * 普通测试运行时空跑（需要 -Dprompt.dump=true 才执行）:
 *   ./gradlew :plugin-concise:testDebugUnitTest --tests "*PromptDump*" -Dprompt.dump=true
 * 产物: <repo>/scripts/prompt_compare/{zh,en}_{before,after}.txt
 *   before = buildSystemPrompt() 原样; after = 应用 ConciseMiddleware 变换（同壳层链）
 */
class PromptDump {

    @Before
    fun setUp() {
        runBlocking {
            val pm = PluginManager.globalInstance
            if (pm.get(ConcisePlugin.PLUGIN_ID) == null) {
                pm.install(ConcisePlugin())
            }
            pm.activate(ConcisePlugin.PLUGIN_ID)
        }
    }

    @Test
    fun dumpPrompts() {
        // 未开启时显示"跳过"而非"通过"（Assume）— 默认空跑语义更诚实
        org.junit.Assume.assumeTrue(
            "需要 -Dprompt.dump=true 才执行",
            System.getProperty("prompt.dump") == "true"
        )
        val engine = PromptEngine()
        // Gradle 测试进程 cwd 不稳定，必须显式传绝对目录
        val dir = File(System.getProperty("prompt.dump.dir")
            ?: error("PromptDump 需要 -Dprompt.dump.dir=<绝对输出目录>"))
        dir.mkdirs()

        for ((lang, tag) in listOf(PromptEngine.AgentLanguage.CHINESE to "zh", PromptEngine.AgentLanguage.ENGLISH to "en")) {
            val before = engine.buildSystemPrompt(lang = lang, agentName = "MengPaw", framework = null, modelName = "test-model")
            val after = ConciseMiddleware.onSystemPrompt(before, "MengPaw")
            File(dir, "${tag}_before.txt").writeText(before)
            File(dir, "${tag}_after.txt").writeText(after)
            println("PromptDump: ${tag}_before.txt (${before.length} chars) / ${tag}_after.txt (${after.length} chars)")
        }
    }
}
