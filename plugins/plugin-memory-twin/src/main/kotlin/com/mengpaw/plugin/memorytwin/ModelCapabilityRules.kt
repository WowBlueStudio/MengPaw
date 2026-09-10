// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 能力结论的来源 — 决定路由该给多少信任分 (见 [TwinRouter])。
 * 排序即优先级: 实测 > 外置规则 > 内置规则 > 档位推断 > 未知。
 */
enum class CapabilitySource { LEARNED, EXTERNAL, BUILTIN, GUESS, UNKNOWN }

/**
 * 一条模型能力规则。**每个维度独立表态** (null = 本规则对该维度不表态):
 * 这是对旧实现"首个关键词命中就决定一切"的修正 —— 旧写法里 `flash` / `mini` / `pro`
 * 这类变体词会先于厂商族命中, 于是 `deepseek-flash`(旗舰) 被判 MEDIUM、
 * `gpt-5.4-mini` 被判 BASIC, 型号一换代判定就错, 且错得无声无息。
 */
@Serializable
data class ModelRule(
    val id: String,
    /** 匹配模型名 (大小写不敏感正则)。 */
    val match: String,
    /** 可选的 provider 名正则 (ProviderInfo.name, 如 `deepseek`); 空 = 不限。 */
    val provider: String = "",
    val quality: ModelQuality? = null,
    val vision: Boolean? = null,
    val ctxTokens: Int? = null,
    val tools: Boolean? = null,
    /** 依据/备注 (官方文档口径), 供 `twin.model` 展示 — 让 Agent 知道这条规则凭什么。 */
    val note: String = ""
)

/** 工作区规则文件 `{agent}/twin-model-rules.json` 的结构。 */
@Serializable
data class ModelRulesFile(
    val version: Int = 1,
    val rules: List<ModelRule> = emptyList()
)

/** 逐维度解析结果 (未表态的维度为 null, 由路由中性处理)。 */
data class ResolvedCapability(
    val quality: ModelQuality,
    val qualitySource: CapabilitySource,
    val vision: Boolean?,
    val visionSource: CapabilitySource,
    val ctxTokens: Int?,
    val ctxSource: CapabilitySource,
    val tools: Boolean?,
    val toolsSource: CapabilitySource,
    val matchedRuleId: String?
)

/**
 * 模型能力规则表 (2026-09-10 进化定案) — 取代硬编码在能力采集里的 if-else 关键词链。
 *
 * ## 为什么要进化
 * LLM 迭代速度已远超发版节奏: 型号 id、上下文长度、多模态能力几乎每月变动。旧实现把
 * "哪个名字算旗舰 / 上下文多长 / 会不会看图"写死在代码里, 于是每次模型换代都要改代码 + 发版,
 * 而且判错会**直接污染路由决策** — 活样本: DeepSeek 把 `…-vision-exp` 换成规范 id
 * `deepseek-flash` 后, 视觉能力判定立刻回归为 false (旧代码只认名字里有没有 "vision"),
 * 孪生随即认为 DeepSeek 不会看图, 弃用它去处理视觉任务。
 *
 * ## 四层来源 (逐维度独立合并, 优先级从高到低)
 * 1. **LEARNED** — 本机实测证据 ([ModelEvidenceStore]): 真实使用中被证实或被拒绝的能力。
 * 2. **EXTERNAL** — 工作区规则文件 `{agent}/twin-model-rules.json`: Agent 自己就能登记新模型,
 *    且该文件随孪生工作区同步扩散到全部设备 —— **一处学到, 全网共享**, 这就是"顺应潮流"的机制。
 * 3. **BUILTIN** — 本对象的 [BUILTIN] 表: **刻意只写厂商族级事实** (族级档位 + 有官方依据的精确能力)。
 *    "某型号上下文是 128K" 这类内容会随版本腐烂, 故不内置, 交给外置规则与实测证据。
 * 4. **GUESS** — 由质量档位推断的上下文兜底 (HIGH→128K / MEDIUM→32K), 路由只给部分分。
 *
 * 全都没命中 → `UNKNOWN`, 路由**中性对待** (不奖不罚), 而不是像旧实现那样兜底成 `BASIC`
 * 把不认识的新模型永远压在旧型号之下 —— 新模型往往正是最强的那个。
 */
object ModelCapabilityRules {

    /** 外置规则文件名 (工作区根, 随孪生同步)。 */
    const val RULES_FILE_NAME = "twin-model-rules.json"

    /** 外置规则条数上限 — 文件可能来自对端同步, 需设界。 */
    const val EXTERNAL_RULE_LIMIT = 200

    /** 单条正则长度上限 (对端可控输入, 限制灾难性回溯面)。 */
    private const val PATTERN_LIMIT = 200

    /** 规则文件大小上限。 */
    private const val FILE_SIZE_LIMIT = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 已编译正则缓存 (key = pattern, null = 编译失败)。 */
    private val regexCache = ConcurrentHashMap<String, Regex?>()

    /** 外置规则缓存 (key = agentName, value = 文件 mtime + 规则表)。 */
    private val externalCache = ConcurrentHashMap<String, Pair<Long, List<ModelRule>>>()

    /**
     * 内置规则 — 只登记**厂商族级**档位与有官方依据的精确事实。
     *
     * 族级定位 (谁家旗舰) 的迭代速度远低于具体型号, 因此这张表可以长期不动;
     * 型号级能力 (多模态/上下文/工具) 由外置规则与实测证据补齐。
     */
    internal val BUILTIN: List<ModelRule> = listOf(
        // ── ① 精确事实 (官方文档口径, 换代时由外置规则覆盖) ──────────────
        ModelRule(
            id = "deepseek-flash-v41",
            match = "^deepseek-flash$",
            quality = ModelQuality.HIGH,
            vision = true,
            ctxTokens = 1_000_000,
            tools = true,
            note = "DeepSeek V4.1 Flash 官方: 原生多模态视觉理解 + 1M 上下文 (2026-09-10)"
        ),
        ModelRule(
            id = "deepseek-v4-legacy",
            match = "^deepseek-v4",
            quality = ModelQuality.HIGH,
            vision = true,
            ctxTokens = 1_000_000,
            tools = true,
            note = "DeepSeek V4 系列旧 id — 官方已下线并路由到 V4.1 Flash, 保留以兼容旧能力卡/旧证据"
        ),
        // ── ② 厂商族级档位 (稳定层) ──────────────────────────────────
        ModelRule("family-deepseek", "^deepseek", quality = ModelQuality.HIGH, note = "DeepSeek 旗舰族"),
        ModelRule("family-openai-flagship", "^gpt-5|^gpt-4o|^gpt-4\\.|^o[0-9]", quality = ModelQuality.HIGH, note = "OpenAI 旗舰族"),
        ModelRule("family-anthropic", "^claude", quality = ModelQuality.HIGH, note = "Anthropic Claude 族"),
        ModelRule("family-google", "^gemini", quality = ModelQuality.HIGH, note = "Google Gemini 族"),
        ModelRule("family-xai", "^grok", quality = ModelQuality.HIGH, note = "xAI Grok 族"),
        ModelRule("family-qwen", "^qwen|^qwq|dashscope", quality = ModelQuality.MEDIUM, note = "通义千问族"),
        ModelRule("family-glm", "^glm|zhipu|bigmodel", quality = ModelQuality.MEDIUM, note = "智谱 GLM 族"),
        ModelRule("family-kimi", "^kimi|moonshot", quality = ModelQuality.MEDIUM, note = "月之暗面 Kimi 族"),
        ModelRule("family-minimax", "^minimax|^abab", quality = ModelQuality.MEDIUM, note = "MiniMax 族"),
        ModelRule("family-doubao", "^doubao|^seed|volces", quality = ModelQuality.MEDIUM, note = "字节豆包族"),
        ModelRule("family-llama", "^llama|^mixtral|^mistral", quality = ModelQuality.MEDIUM, note = "开源 Llama/Mistral 族"),
        ModelRule("family-domestic", "^ernie|^hunyuan|^step|^baichuan|^yi-|^spark", quality = ModelQuality.MEDIUM, note = "其它国产旗舰族"),
        // ── ③ 明确的小尺寸档 (只降档, 不覆盖已由精确/族级规则表态的更高档) ──
        ModelRule("variant-tiny", "(^|[-_])(tiny|nano|micro|lite|mini|small)([-_]|$)", quality = ModelQuality.BASIC, note = "小尺寸变体档")
    )

    // ── 外置规则 (工作区文件) ───────────────────────────────────────

    /** 外置规则文件路径 — `{AGENTS}/{agent}/twin-model-rules.json`。 */
    fun rulesFilePath(agentName: String): File =
        File(DataPaths.AGENTS, "$agentName/$RULES_FILE_NAME")

    /**
     * 读取工作区外置规则。任何异常都不影响能力采集 (返回空表 + 上抛错误收集):
     * 文件来自对端同步, 必须假定其可能损坏或被构造。
     */
    fun loadExternal(agentName: String): List<ModelRule> {
        if (agentName.isBlank()) return emptyList()
        val file = rulesFilePath(agentName)
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            if (file.length() > FILE_SIZE_LIMIT) {
                ErrorCollector.report(
                    IllegalArgumentException("${RULES_FILE_NAME} 超限 (${file.length()} > $FILE_SIZE_LIMIT), 已忽略"),
                    "ModelCapabilityRules.loadExternal"
                )
                return emptyList()
            }
            parseExternal(file.readText())
        } catch (e: Exception) {
            ErrorCollector.report(e, "ModelCapabilityRules.loadExternal(${file.name})")
            emptyList()
        }
    }

    /**
     * 带缓存的读取 (按文件 mtime 失效) — 能力卡采集会被电量/网络广播频繁触发,
     * 每次都重读工作区文件没有必要。
     */
    fun loadExternalCached(agentName: String): List<ModelRule> {
        if (agentName.isBlank()) return emptyList()
        val file = rulesFilePath(agentName)
        val stamp = if (file.exists()) file.lastModified() else -1L
        externalCache[agentName]?.let { cached -> if (cached.first == stamp) return cached.second }
        val rules = loadExternal(agentName)
        externalCache[agentName] = stamp to rules
        return rules
    }

    /** 解析外置规则 JSON — 容错: 坏条目跳过而不是整表失效 (对端同步来的数据不可信)。 */
    fun parseExternal(text: String): List<ModelRule> {
        val parsed = try {
            json.decodeFromString(ModelRulesFile.serializer(), text)
        } catch (_: Exception) {
            return emptyList()
        }
        return parsed.rules
            .asSequence()
            .filter { it.match.isNotBlank() && it.match.length <= PATTERN_LIMIT }
            .filter { compile(it.match) != null }
            .take(EXTERNAL_RULE_LIMIT)
            .toList()
    }

    // ── 解析 ──────────────────────────────────────────────────────

    /**
     * 逐维度解析模型能力。优先级: 证据(LEARNED) > 外置(EXTERNAL) > 内置(BUILTIN) > 档位推断(GUESS)。
     *
     * @param modelName ProviderInfo.model (如 `deepseek-flash`)
     * @param providerName ProviderInfo.name (如 `deepseek`); 供规则的 provider 限定使用
     * @param external 工作区外置规则 ([loadExternal])
     * @param evidence 本机实测证据 ([ModelEvidenceStore.evidenceFor]); null = 无实测
     */
    fun resolve(
        modelName: String,
        providerName: String = "",
        external: List<ModelRule> = emptyList(),
        evidence: ModelEvidence? = null
    ): ResolvedCapability {
        val model = modelName.trim()
        if (model.isEmpty()) {
            return ResolvedCapability(
                ModelQuality.UNKNOWN, CapabilitySource.UNKNOWN,
                null, CapabilitySource.UNKNOWN, null, CapabilitySource.UNKNOWN,
                null, CapabilitySource.UNKNOWN, null
            )
        }
        val externalHit = firstHit(model, providerName, external)?.let { it to CapabilitySource.EXTERNAL }
        val builtinHit = firstHit(model, providerName, BUILTIN)?.let { it to CapabilitySource.BUILTIN }

        // ── 质量档 ──
        val externalQuality = externalHit?.first?.quality
        val builtinQuality = builtinHit?.first?.quality
        val quality: ModelQuality
        val qualitySource: CapabilitySource
        when {
            externalQuality != null -> { quality = externalQuality; qualitySource = CapabilitySource.EXTERNAL }
            builtinQuality != null -> { quality = builtinQuality; qualitySource = CapabilitySource.BUILTIN }
            else -> { quality = ModelQuality.UNKNOWN; qualitySource = CapabilitySource.UNKNOWN }
        }

        // ── 视觉能力 (实测 > 外置 > 内置; 猜不出来, 不存在就不表态) ──
        val vision = when {
            (evidence?.visionRejected ?: 0) > 0 && (evidence?.visionOk ?: 0) == 0 -> false
            (evidence?.visionOk ?: 0) > 0 -> true
            externalHit?.first?.vision != null -> externalHit.first.vision
            builtinHit?.first?.vision != null -> builtinHit.first.vision
            else -> null
        }
        val visionSource = when {
            evidence != null && ((evidence.visionRejected > 0) || evidence.visionOk > 0) -> CapabilitySource.LEARNED
            externalHit?.first?.vision != null -> CapabilitySource.EXTERNAL
            builtinHit?.first?.vision != null -> CapabilitySource.BUILTIN
            else -> CapabilitySource.UNKNOWN
        }

        // ── 上下文 (实测优先: 官方声明 1M 但实测 200K 就溢出时, 听实测的) ──
        val declaredCtx = externalHit?.first?.ctxTokens ?: builtinHit?.first?.ctxTokens
        val declaredSource = if (externalHit?.first?.ctxTokens != null) CapabilitySource.EXTERNAL
        else if (builtinHit?.first?.ctxTokens != null) CapabilitySource.BUILTIN else CapabilitySource.UNKNOWN
        val observed = evidence?.maxObservedContext ?: 0
        val overflowAt = evidence?.ctxOverflowAt ?: 0
        val ctx: Int?
        val ctxSource: CapabilitySource
        when {
            overflowAt > 0 && (declaredCtx == null || overflowAt < declaredCtx) -> { ctx = overflowAt; ctxSource = CapabilitySource.LEARNED }
            observed > 0 && (declaredCtx == null || observed >= declaredCtx) -> { ctx = observed; ctxSource = CapabilitySource.LEARNED }
            declaredCtx != null -> { ctx = declaredCtx; ctxSource = declaredSource }
            else -> {
                val guess = guessCtxFor(quality)
                ctx = guess
                ctxSource = if (guess != null) CapabilitySource.GUESS else CapabilitySource.UNKNOWN
            }
        }

        // ── 工具调用 (实测 > 外置 > 内置 > 档位推导: 旗舰/中档默认具备) ──
        val tools = when {
            (evidence?.toolsRejected ?: 0) > 0 && (evidence?.toolsOk ?: 0) == 0 -> false
            (evidence?.toolsOk ?: 0) > 0 -> true
            externalHit?.first?.tools != null -> externalHit.first.tools
            builtinHit?.first?.tools != null -> builtinHit.first.tools
            quality == ModelQuality.HIGH || quality == ModelQuality.MEDIUM -> true
            quality == ModelQuality.BASIC -> false
            else -> null
        }
        val toolsSource = when {
            evidence != null && (evidence.toolsRejected > 0 || evidence.toolsOk > 0) -> CapabilitySource.LEARNED
            externalHit?.first?.tools != null -> CapabilitySource.EXTERNAL
            builtinHit?.first?.tools != null -> CapabilitySource.BUILTIN
            tools != null -> CapabilitySource.GUESS
            else -> CapabilitySource.UNKNOWN
        }

        return ResolvedCapability(
            quality = quality,
            qualitySource = qualitySource,
            vision = vision,
            visionSource = visionSource,
            ctxTokens = ctx,
            ctxSource = ctxSource,
            tools = tools,
            toolsSource = toolsSource,
            matchedRuleId = externalHit?.first?.id ?: builtinHit?.first?.id
        )
    }

    /** 档位推断的上下文兜底 (来源标记为 GUESS, 路由只给部分分)。 */
    internal fun guessCtxFor(quality: ModelQuality): Int? = when (quality) {
        ModelQuality.HIGH -> 128_000
        ModelQuality.MEDIUM -> 32_000
        ModelQuality.BASIC -> 8_000
        ModelQuality.UNKNOWN -> null
    }

    /** 首个命中规则 (外置优先于内置由调用方保证顺序)。 */
    private fun firstHit(model: String, providerName: String, rules: List<ModelRule>): ModelRule? =
        rules.firstOrNull { rule ->
            val modelOk = compile(rule.match)?.containsMatchIn(model) == true
            val providerOk = rule.provider.isBlank() ||
                compile(rule.provider)?.containsMatchIn(providerName) == true
            modelOk && providerOk
        }

    /** 编译正则 (带缓存; 失败返回 null 而非抛异常 — 坏规则只是不生效)。 */
    private fun compile(pattern: String): Regex? =
        regexCache.computeIfAbsent(pattern) {
            try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { null }
        }

    /** 供 `twin.model rules` 展示: 生效规则 + 来源。 */
    fun describeAll(external: List<ModelRule>): String = buildString {
        appendLine("外部规则 (工作区文件, 随孪生同步) — ${external.size} 条")
        if (external.isEmpty()) {
            appendLine("  (无 — 创建 ${RULES_FILE_NAME} 即可登记新模型, 无需改代码/发版)")
        } else {
            external.forEach { appendLine("  · ${it.id} | ${it.match} | ${it.describe()}") }
        }
        appendLine()
        appendLine("内置规则 (厂商族级事实) — ${BUILTIN.size} 条")
        BUILTIN.forEach { appendLine("  · ${it.id} | ${it.match} | ${it.describe()}") }
    }

    private fun ModelRule.describe(): String {
        val parts = buildList {
            quality?.let { add("档=$it") }
            vision?.let { add("视觉=$it") }
            ctxTokens?.let { add("上下文=${it / 1000}K") }
            tools?.let { add("工具=$it") }
        }
        return if (parts.isEmpty()) "(不表态)" else parts.joinToString(" ")
    }

    /** 测试/重载用: 清空规则缓存。 */
    internal fun clearCache() {
        regexCache.clear()
        externalCache.clear()
    }
}
