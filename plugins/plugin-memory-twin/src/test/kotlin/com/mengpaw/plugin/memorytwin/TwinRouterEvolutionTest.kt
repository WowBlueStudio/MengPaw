// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由中性化测试 (2026-09-10 进化) — 锁死三条新语义:
 *
 * 1. **未知能力不扣分**: 不认识的模型 (UNKNOWN / 上下文未知 / 视觉未知) 按中性 0 分处理,
 *    旧实现把它们当作"弱"直接扣分, 于是最新最强的模型永远排在旧型号之后。
 * 2. **推断值只给部分分**: 由档位猜出的上下文 (+8) < 实测/声明/规则给出的 (+20)。
 * 3. **结论可追溯**: 优势/不足文案带上来源标注 (实测/外置规则/内置规则/推断)。
 */
class TwinRouterEvolutionTest {

    private fun profile(
        model: String,
        quality: ModelQuality,
        qualitySource: CapabilitySource,
        ctx: Int = 0,
        ctxSource: CapabilitySource = CapabilitySource.UNKNOWN,
        vision: Boolean = false,
        visionSource: CapabilitySource = CapabilitySource.UNKNOWN
    ) = ModelProfile(
        providerName = "test",
        modelName = model,
        providerType = "REMOTE",
        contextWindowTokens = ctx,
        supportsVision = vision,
        supportsTools = true,
        estimatedQuality = quality,
        qualitySource = qualitySource,
        ctxSource = ctxSource,
        visionSource = visionSource
    )

    private fun card(model: ModelProfile) = CapabilityCard(
        deviceId = "dev-1",
        deviceName = "测试设备",
        deviceModel = "test-model",
        formFactor = FormFactor.PHONE,
        hardware = HardwareProfile(
            cpuCores = 8, ramTotalMB = 8192, storageFreeMB = 10_000,
            hasCamera = true, cameraFacing = listOf("back"),
            hasBluetooth = true, hasNfc = false, sensors = emptyList(),
            screenWidth = 1080, screenHeight = 2400,
            batteryLevel = 80, isCharging = false, networkType = "Wifi"
        ),
        model = model,
        software = SoftwareProfile("0.47.0", emptyList(), emptyList(), emptyList()),
        runtime = RuntimeStatus(true, 0, 0, null, false)
    )

    private fun scoreOf(task: String, model: ModelProfile): Int =
        TwinRouter.route(task, card(model), emptyList()).recommendations.first().score

    private fun analysisOf(task: String, model: ModelProfile) =
        TwinRouter.route(task, card(model), emptyList()).recommendations.first()

    // ── 1. 未知能力中性 ─────────────────────────────────────────

    @Test
    fun `未知档位不扣分_优于被判定为弱的模型`() {
        val unknown = profile("brand-new-model", ModelQuality.UNKNOWN, CapabilitySource.UNKNOWN)
        val weak = profile("old-weak-model", ModelQuality.BASIC, CapabilitySource.BUILTIN)

        val unknownScore = scoreOf("请做深入的架构推理分析", unknown)
        val weakScore = scoreOf("请做深入的架构推理分析", weak)

        assertTrue("未知档位不应被扣分 (实际 $unknownScore)", unknownScore >= 50)
        assertTrue("未知档位应优于已知弱模型 ($unknownScore vs $weakScore)", unknownScore > weakScore)
        assertTrue(
            "应提示能力待实测",
            analysisOf("请做深入的架构推理分析", unknown).strengths.any { it.contains("推理档位未知") }
        )
    }

    @Test
    fun `未知上下文不因大上下文需求被扣分`() {
        val unknownCtx = profile("brand-new-model", ModelQuality.UNKNOWN, CapabilitySource.UNKNOWN)
        val smallCtx = profile(
            "small-ctx-model", ModelQuality.MEDIUM, CapabilitySource.BUILTIN,
            ctx = 8_000, ctxSource = CapabilitySource.BUILTIN
        )

        val unknownScore = scoreOf("分析这份文档并总结", unknownCtx)
        val smallScore = scoreOf("分析这份文档并总结", smallCtx)

        assertTrue("上下文未知应为中性 (实际 $unknownScore)", unknownScore >= 50)
        assertTrue("未知上下文不应输给已知小上下文 ($unknownScore vs $smallScore)", unknownScore > smallScore)
    }

    @Test
    fun `未知视觉不扣分_且提示可实测修正`() {
        val unknownVision = profile("brand-new-model", ModelQuality.HIGH, CapabilitySource.BUILTIN)
        val noVision = profile(
            "no-vision-model", ModelQuality.HIGH, CapabilitySource.BUILTIN,
            vision = false, visionSource = CapabilitySource.BUILTIN
        )

        val unknownScore = scoreOf("识别这张图片里的文字", unknownVision)
        val noVisionScore = scoreOf("识别这张图片里的文字", noVision)

        assertTrue("未知视觉不得被当作不支持扣分 ($unknownScore vs $noVisionScore)", unknownScore > noVisionScore)
        assertTrue(
            "应提示视觉能力未标注",
            analysisOf("识别这张图片里的文字", unknownVision).strengths.any { it.contains("视觉能力未标注") }
        )
        assertTrue(
            "明确不支持才进不足项",
            analysisOf("识别这张图片里的文字", noVision).weaknesses.any { it.contains("不支持视觉") }
        )
    }

    // ── 2. 推断值只给部分分 ─────────────────────────────────────

    @Test
    fun `推断的上下文只给部分分_低于实测或声明`() {
        val guessed = profile(
            "family-model", ModelQuality.HIGH, CapabilitySource.BUILTIN,
            ctx = 128_000, ctxSource = CapabilitySource.GUESS
        )
        val declared = profile(
            "declared-model", ModelQuality.HIGH, CapabilitySource.BUILTIN,
            ctx = 128_000, ctxSource = CapabilitySource.BUILTIN
        )
        val learned = profile(
            "learned-model", ModelQuality.HIGH, CapabilitySource.BUILTIN,
            ctx = 128_000, ctxSource = CapabilitySource.LEARNED
        )

        val guessScore = scoreOf("分析这份文档并总结", guessed)
        val declaredScore = scoreOf("分析这份文档并总结", declared)
        val learnedScore = scoreOf("分析这份文档并总结", learned)

        assertTrue("推断 < 声明 ($guessScore vs $declaredScore)", guessScore < declaredScore)
        assertEquals("实测与声明同等给满分", declaredScore, learnedScore)
    }

    // ── 3. 来源可追溯 ───────────────────────────────────────────

    @Test
    fun `结论带来源标注`() {
        val learned = profile(
            "deepseek-flash", ModelQuality.HIGH, CapabilitySource.LEARNED,
            ctx = 1_000_000, ctxSource = CapabilitySource.LEARNED,
            vision = true, visionSource = CapabilitySource.LEARNED
        )
        val rec = analysisOf("识别这张图片里的文字", learned)
        assertTrue("视觉来源应显示为实测", rec.strengths.any { it.contains("实测") })
        assertTrue("模型名应出现在优势里", rec.strengths.any { it.contains("deepseek-flash") })
    }

    @Test
    fun `能力卡摘要展示每维来源与证据条数`() {
        val p = profile(
            "deepseek-flash", ModelQuality.HIGH, CapabilitySource.BUILTIN,
            ctx = 1_000_000, ctxSource = CapabilitySource.BUILTIN,
            vision = true, visionSource = CapabilitySource.BUILTIN
        ).copy(evidenceCount = 3)
        val summary = p.summary()
        assertTrue(summary, summary.contains("档=HIGH(内置规则)"))
        assertTrue(summary, summary.contains("上下文=1000K(内置规则)"))
        assertTrue(summary, summary.contains("视觉=支持(内置规则)"))
        assertTrue(summary, summary.contains("实测证据=3条"))
    }
}
