// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 模型能力判定进化测试 (2026-09-10) — 锁死"数据与证据取代硬编码"的语义:
 *
 * - 内置表只登记厂商族级事实, 规范 id 的精确能力来自规则而非名字猜测
 * - **族级优先于小尺寸变体词**: `gpt-5.4-mini` 不得因名字含 "mini" 掉到 BASIC
 *   (旧实现顺序即优先级, "mini" 先命中 → 永远进不了 HIGH)
 * - 不认识的模型 → UNKNOWN 中性, **不是** BASIC 垫底
 * - 优先级: 实测证据 > 外置规则 > 内置规则
 * - 外置规则解析对不可信输入设界 (条数/正则长度/编译失败)
 */
class ModelCapabilityRulesTest {

    @Before
    fun setUp() {
        ModelCapabilityRules.clearCache()
        ModelEvidenceStore.invalidateCache()
    }

    @After
    fun tearDown() {
        ModelCapabilityRules.clearCache()
        ModelEvidenceStore.invalidateCache()
        DataPaths.initialize("/sdcard/MengPaw")
    }

    // ── 内置规则 ─────────────────────────────────────────────────

    @Test
    fun `规范id的精确能力来自规则_不再靠名字猜`() {
        val cap = ModelCapabilityRules.resolve("deepseek-flash", "deepseek")
        // DeepSeek V4.1 Flash: 官方称其性能已全面超越 V4 Pro → 旗舰档, 且原生多模态
        assertEquals(ModelQuality.HIGH, cap.quality)
        assertEquals(CapabilitySource.BUILTIN, cap.qualitySource)
        assertEquals(true, cap.vision)
        assertEquals(CapabilitySource.BUILTIN, cap.visionSource)
        assertEquals(1_000_000, cap.ctxTokens)
        assertEquals("deepseek-flash-v41", cap.matchedRuleId)
    }

    @Test
    fun `族级档位优先于小尺寸变体词_旧实现顺序陷阱回归`() {
        // 旧实现的 when 顺序是 pro→flash→turbo→mini→gpt-5…: "gpt-5.4-mini" 先命中 mini → BASIC。
        // 新实现族级规则先表态档位, 变体词只在小尺寸档兜底时生效。
        val flagship = ModelCapabilityRules.resolve("gpt-5.4-mini", "openai")
        assertEquals("含 mini 的旗舰族型号仍应判 HIGH", ModelQuality.HIGH, flagship.quality)

        val tiny = ModelCapabilityRules.resolve("some-vendor-tiny", "custom")
        assertEquals("真正的无名小模型落到 BASIC 兜底", ModelQuality.BASIC, tiny.quality)
    }

    @Test
    fun `不认识的模型是UNKNOWN中性而不是BASIC垫底`() {
        val cap = ModelCapabilityRules.resolve("brand-new-model-x", "newvendor")
        assertEquals(ModelQuality.UNKNOWN, cap.quality)
        assertEquals(CapabilitySource.UNKNOWN, cap.qualitySource)
        // 未表态的维度必须是 null (路由据此中性处理), 而不是被填成 false/8000
        assertNull("视觉未表态", cap.vision)
        assertNull("上下文未表态", cap.ctxTokens)
        assertEquals(CapabilitySource.UNKNOWN, cap.ctxSource)
    }

    // ── 外置规则 (工作区文件, 随孪生同步) ───────────────────────

    @Test
    fun `外置规则覆盖内置_来源标EXTERNAL`() {
        val external = listOf(
            ModelRule(
                id = "custom-override",
                match = "^deepseek-flash$",
                quality = ModelQuality.MEDIUM,
                ctxTokens = 200_000,
                note = "用户自定: 实测该网关只给 200K"
            )
        )
        val cap = ModelCapabilityRules.resolve("deepseek-flash", "deepseek", external = external)
        assertEquals(ModelQuality.MEDIUM, cap.quality)
        assertEquals(CapabilitySource.EXTERNAL, cap.qualitySource)
        assertEquals(200_000, cap.ctxTokens)
        assertEquals("custom-override", cap.matchedRuleId)
        // 外置规则没表态的维度继续走内置 (逐维度合并, 而非整条规则覆盖)
        assertEquals(true, cap.vision)
        assertEquals(CapabilitySource.BUILTIN, cap.visionSource)
    }

    @Test
    fun `新模型可由外置规则登记_零代码零发版`() {
        val external = listOf(
            ModelRule(
                id = "next-gen-flagship",
                match = "^future-model-9",
                quality = ModelQuality.HIGH,
                vision = true,
                ctxTokens = 2_000_000,
                note = "2026-10 新旗舰"
            )
        )
        val cap = ModelCapabilityRules.resolve("future-model-9-turbo", "vendorz", external = external)
        assertEquals(ModelQuality.HIGH, cap.quality)
        assertEquals(CapabilitySource.EXTERNAL, cap.qualitySource)
        assertEquals(2_000_000, cap.ctxTokens)
        assertEquals(true, cap.vision)
    }

    @Test
    fun `外置规则解析_坏条目跳过而不是整表失效`() {
        val json = """
            {
              "version": 1,
              "rules": [
                { "id": "good", "match": "^good-model", "quality": "HIGH" },
                { "id": "bad-regex", "match": "([unclosed", "quality": "HIGH" },
                { "id": "empty-match", "match": "", "quality": "HIGH" },
                { "id": "too-long", "match": "${"a".repeat(201)}" }
              ]
            }
        """.trimIndent()
        val rules = ModelCapabilityRules.parseExternal(json)
        assertEquals("只保留合法条目", listOf("good"), rules.map { it.id })
    }

    @Test
    fun `外置规则解析_坏JSON返回空表不抛异常`() {
        assertTrue(ModelCapabilityRules.parseExternal("not json at all").isEmpty())
        assertTrue(ModelCapabilityRules.parseExternal("").isEmpty())
    }

    @Test
    fun `外置规则条数上限`() {
        val rules = (1..(ModelCapabilityRules.EXTERNAL_RULE_LIMIT + 20)).joinToString(",") {
            """{ "id": "r$it", "match": "^model-$it", "quality": "MEDIUM" }"""
        }
        val parsed = ModelCapabilityRules.parseExternal("""{ "version": 1, "rules": [$rules] }""")
        assertEquals(ModelCapabilityRules.EXTERNAL_RULE_LIMIT, parsed.size)
    }

    @Test
    fun `工作区规则文件被读取并生效`() {
        val base = File(System.getProperty("java.io.tmpdir"), "mengpaw-rules-${System.nanoTime()}")
        DataPaths.initialize(base.absolutePath)
        val agent = "agent-rules"
        val file = ModelCapabilityRules.rulesFilePath(agent)
        file.parentFile?.mkdirs()
        file.writeText(
            """{ "version": 1, "rules": [ { "id": "ws-rule", "match": "^local-llm", "quality": "BASIC", "ctxTokens": 8192 } ] }"""
        )
        ModelCapabilityRules.clearCache()

        val loaded = ModelCapabilityRules.loadExternal(agent)
        assertEquals(1, loaded.size)

        val cap = ModelCapabilityRules.resolve("local-llm-7b", "selfhost", external = loaded)
        assertEquals(ModelQuality.BASIC, cap.quality)
        assertEquals(8_192, cap.ctxTokens)

        base.deleteRecursively()
    }

    @Test
    fun `超大规则文件被忽略`() {
        val base = File(System.getProperty("java.io.tmpdir"), "mengpaw-rules-big-${System.nanoTime()}")
        DataPaths.initialize(base.absolutePath)
        val agent = "agent-big"
        val file = ModelCapabilityRules.rulesFilePath(agent)
        file.parentFile?.mkdirs()
        file.writeText("x".repeat(70 * 1024))
        ModelCapabilityRules.clearCache()

        assertTrue("超 64KB 的规则文件不应被解析", ModelCapabilityRules.loadExternal(agent).isEmpty())
        base.deleteRecursively()
    }

    // ── 实测证据优先 ─────────────────────────────────────────────

    @Test
    fun `实测证据优先于内置声明_视觉被否证`() {
        val evidence = ModelEvidence(
            model = "deepseek-flash", provider = "deepseek",
            visionOk = 0, visionRejected = 2, updatedAt = System.currentTimeMillis()
        )
        val cap = ModelCapabilityRules.resolve("deepseek-flash", "deepseek", evidence = evidence)
        assertEquals("实测被拒 → 视觉 false", false, cap.vision)
        assertEquals(CapabilitySource.LEARNED, cap.visionSource)
        // 未被证据触及的维度仍走声明
        assertEquals(ModelQuality.HIGH, cap.quality)
    }

    @Test
    fun `实测证据优先于内置声明_视觉被证实`() {
        val evidence = ModelEvidence(model = "brand-new-model-x", provider = "newvendor", visionOk = 1)
        val cap = ModelCapabilityRules.resolve("brand-new-model-x", "newvendor", evidence = evidence)
        assertEquals(true, cap.vision)
        assertEquals(CapabilitySource.LEARNED, cap.visionSource)
    }

    @Test
    fun `实测上下文溢出取保守上界_覆盖夸大的声明`() {
        // 声明 1M, 但实测 300K 就溢出 → 听实测的
        val evidence = ModelEvidence(
            model = "deepseek-flash", provider = "deepseek",
            maxObservedContext = 0, ctxOverflowAt = 300_000
        )
        val cap = ModelCapabilityRules.resolve("deepseek-flash", "deepseek", evidence = evidence)
        assertEquals(300_000, cap.ctxTokens)
        assertEquals(CapabilitySource.LEARNED, cap.ctxSource)
    }

    @Test
    fun `实测上下文成功值在声明缺失时补齐`() {
        val evidence = ModelEvidence(model = "brand-new-model-x", maxObservedContext = 640_000)
        val cap = ModelCapabilityRules.resolve("brand-new-model-x", "newvendor", evidence = evidence)
        assertEquals(640_000, cap.ctxTokens)
        assertEquals(CapabilitySource.LEARNED, cap.ctxSource)
    }

    @Test
    fun `推断上下文来源标GUESS`() {
        // 只认识厂商族 (无上下文声明) → 由档位推断, 路由只给部分分
        val cap = ModelCapabilityRules.resolve("qwen-next-gen", "dashscope")
        assertEquals(ModelQuality.MEDIUM, cap.quality)
        assertEquals(ModelCapabilityRules.guessCtxFor(ModelQuality.MEDIUM), cap.ctxTokens)
        assertEquals(CapabilitySource.GUESS, cap.ctxSource)
    }

    @Test
    fun `空模型名返回UNKNOWN画像`() {
        val cap = ModelCapabilityRules.resolve("", "deepseek")
        assertEquals(ModelQuality.UNKNOWN, cap.quality)
        assertNull(cap.ctxTokens)
        assertNull(cap.vision)
    }
}
