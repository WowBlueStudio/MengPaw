// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.llm.LlmProvider

/**
 * 模型能力画像解析 (2026-09-10 进化) — 取代 [TwinCapabilityCollector] 里原先的硬编码关键词链。
 *
 * 旧实现 (已删除) 是这样判的:
 * ```
 * info.model.contains("pro")    -> HIGH      // "pro" 只是名字片段, 与档位无必然关系
 * info.model.contains("flash")  -> MEDIUM    // DeepSeek 的 flash 恰是当前旗舰 → 判错
 * info.model.contains("mini")   -> BASIC     // gpt-5.4-mini 先命中这条, 永远进不了 HIGH
 * ...
 * else -> BASIC                              // 不认识的新模型一律垫底
 * ```
 * 每条都是"名字猜测", 且顺序即优先级 —— 型号一换代就静默判错, 而判错会直接改变路由结论。
 *
 * 现在: **实测证据 > 外置规则 > 内置规则(厂商族级) > 档位推断 > 中性未知**,
 * 每个维度独立合并, 并在能力卡里带上来源标记 ([ModelProfile.qualitySource] 等)。
 */
object ModelProfileResolver {

    /**
     * 解析本机当前模型的能力画像。
     *
     * @param llmProvider 当前 LLM provider (null = 未配置, 返回 UNKNOWN 画像)
     * @param agentName 用于定位工作区外置规则文件 `{agent}/twin-model-rules.json`
     */
    fun resolve(llmProvider: LlmProvider?, agentName: String = ""): ModelProfile {
        if (llmProvider == null) return unknownProfile()

        val info = try {
            llmProvider.info()
        } catch (e: Exception) {
            ErrorCollector.report(e, "ModelProfileResolver.resolve(info)")
            return unknownProfile()
        }

        val external = if (agentName.isBlank()) emptyList() else ModelCapabilityRules.loadExternalCached(agentName)
        val evidence = ModelEvidenceStore.evidenceFor(info.model, info.name)
        val cap = ModelCapabilityRules.resolve(
            modelName = info.model,
            providerName = info.name,
            external = external,
            evidence = evidence
        )

        return ModelProfile(
            providerName = info.name,
            modelName = info.model,
            providerType = info.providerType.name,
            // 未知上下文记 0 (而不是编一个数字) — 路由据此中性处理
            contextWindowTokens = cap.ctxTokens ?: 0,
            supportsVision = cap.vision == true,
            supportsTools = cap.tools == true,
            estimatedQuality = cap.quality,
            qualitySource = cap.qualitySource,
            ctxSource = cap.ctxSource,
            visionSource = cap.visionSource,
            toolsSource = cap.toolsSource,
            evidenceCount = evidence?.factCount ?: 0,
            matchedRuleId = cap.matchedRuleId
        )
    }

    /**
     * 记录一条实测事实并按最新证据重算画像 (用中学的入口)。
     *
     * @return 记录后的画像 — 调用方 (命令层) 可直接回显"判定因此变化"。
     */
    fun observe(
        llmProvider: LlmProvider?,
        agentName: String,
        model: String,
        provider: String,
        fact: EvidenceFact,
        value: Int = 0
    ): ModelProfile {
        ModelEvidenceStore.record(model, provider, fact, value)
        return resolve(llmProvider, agentName)
    }

    private fun unknownProfile() = ModelProfile(
        providerName = "unknown",
        modelName = "unknown",
        providerType = "UNKNOWN",
        contextWindowTokens = 0,
        supportsVision = false,
        supportsTools = false,
        estimatedQuality = ModelQuality.UNKNOWN
    )
}
