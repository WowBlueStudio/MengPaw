// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ── 设置数据模型 — 拆自 SettingsViewModel.kt (2026-08-06, >400 行文件拆分批次4) ──
// 命名避开同名冲突 — 各对话框文件已有自己的 private appJson (FrameworkCardDialog 等)。

internal val settingsAppJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
internal data class SavedProviderJson(
    val preset: String,
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val balance: String = ""
)

/**
 * Preset LLM providers with known endpoints and models.
 */
data class ModelInfo(val name: String, val type: String) // type: "Chat" or "多模态"

enum class LlmProviderPreset(
    val label: String,
    val enLabel: String,
    val endpoint: String,
    val defaultModel: String,
    val apiKeyPrefix: String = "",
    val models: List<ModelInfo> = emptyList()
) {
    // ═══ Presets verified against official docs — 2026-08-17 ═══
    // Only top models listed here; full list fetched from API on key entry.
    // OpenAI 2026-08-17 核对 (developers.openai.com/api/docs/models): GPT-5.6 家族为当前旗舰
    // (gpt-5.6 别名路由到 GPT-5.6 Sol, 1.05M 上下文; Terra 均衡 / Luna 轻量); o4-mini 官方标记
    // Deprecated 已移除, gpt-5.5 / gpt-5.4 为前代。
    OPENAI("OpenAI", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5.6", "sk-",
        listOf(ModelInfo("gpt-5.6", "旗舰·1.05M上下文"), ModelInfo("gpt-5.6-terra", "均衡"),
            ModelInfo("gpt-5.6-luna", "轻量"), ModelInfo("gpt-5.5", "前代"), ModelInfo("gpt-5.4", "前代"))),
    DEEPSEEK("DeepSeek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-v4-flash", "sk-",
        listOf(ModelInfo("deepseek-v4-flash", "快速"), ModelInfo("deepseek-v4-pro", "思维链"))),
    KIMI("Kimi (月之暗面)", "Kimi (Moonshot)", "https://api.moonshot.cn/v1/chat/completions", "kimi-k3", "sk-",
        listOf(ModelInfo("kimi-k3", "旗舰·1M上下文"), ModelInfo("kimi-k2.7-code", "Coding"),
            ModelInfo("kimi-k2.6", "通用"), ModelInfo("kimi-k2.7-code-highspeed", "高速Coding"))),
    GLM("GLM (智谱)", "GLM (Zhipu)", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-5.2", "",
        listOf(ModelInfo("glm-5.2", "旗舰·1M上下文"), ModelInfo("glm-5.1", "Coding"),
            ModelInfo("glm-5", "前代"), ModelInfo("glm-5-turbo", "高速"), ModelInfo("glm-5v-turbo", "多模态"))),
    // DashScope 2026-08-17 核对 (help.aliyun.com/zh/model-studio/getting-started/models +
    // 官方 Responses 兼容模型清单): qwen3.8-max 已转正为旗舰 (qwen3.8-max-preview 退役自动路由);
    // 均衡/快速档当前为 qwen3.7-plus / qwen3.7-flash。
    QWEN("DashScope", "DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3.8-max", "sk-",
        listOf(ModelInfo("qwen3.8-max", "旗舰·视觉+推理"), ModelInfo("qwen3.7-max", "前代"),
            ModelInfo("qwen3.7-plus", "均衡·视觉"), ModelInfo("qwen3.7-flash", "快速·视觉"),
            ModelInfo("qwen3.6-35b-a3b", "开源MoE"),
            ModelInfo("qwen3-coder-plus", "Coding"), ModelInfo("qwq-plus", "思维链"),
            ModelInfo("qwen3-vl-plus", "多模态"), ModelInfo("qwen3-omni-flash", "全模态"))),
    // Grok 2026-08-17 核对 (docs.x.ai/developers/models + 退役公告): grok-4.6 为当前旗舰 (500K);
    // grok-4.1-fast-non-reasoning 官方 2026-05-15 退役 (自动重定向 grok-4.3) 已移除;
    // grok-4.20-reasoning 为 grok-4.20-0309-reasoning 的别名, 取官方规范名。
    GROK("Grok (xAI)", "Grok (xAI)", "https://api.x.ai/v1/chat/completions", "grok-4.6", "xai-",
        listOf(ModelInfo("grok-4.6", "旗舰·500K上下文"), ModelInfo("grok-4.5", "前代"),
            ModelInfo("grok-4.3", "推荐·1M上下文"), ModelInfo("grok-4.20-0309-reasoning", "思维链"),
            ModelInfo("grok-build-0.1", "Coding"))),
    // 火山 2026-08-17 核对 (docs.volcengine.com 套餐概览 + OpenCode 配置): 托管第三方当前为
    // deepseek-v4-flash / deepseek-v4-pro / glm-5.3 (glm-5.2 即将下线, 原 deepseek-v3-2/glm-4.7
    // 已过时替换); 新增官方 2.1 系列 doubao-seed-2.1-turbo。
    VOLCANO("火山引擎 (豆包)", "Volcano Engine (Doubao)", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-seed-2.0-pro", "",
        listOf(ModelInfo("doubao-seed-2.0-pro", "旗舰"), ModelInfo("doubao-seed-2.1-turbo", "2.1系列·快速"),
            ModelInfo("doubao-seed-2.0-lite", "均衡"), ModelInfo("doubao-seed-2.0-mini", "轻量"),
            ModelInfo("doubao-seed-1.8", "前代"),
            ModelInfo("doubao-seed-1.6-flash", "快速"), ModelInfo("doubao-seed-1.6-thinking", "思维链"),
            ModelInfo("deepseek-v4-flash", "DeepSeek托管"), ModelInfo("deepseek-v4-pro", "DeepSeek托管·思维链"),
            ModelInfo("glm-5.3", "GLM托管"),
            ModelInfo("(需创建接入点 ep-xxx)", "提示"))),
    OPENMODEL("OpenModel", "OpenModel", "https://api.openmodel.ai/v1/chat/completions", "deepseek-v4-flash", "sk-",
        listOf(ModelInfo("deepseek-v4-pro", "思维链"), ModelInfo("deepseek-v4-flash", "快速"),
            ModelInfo("qwen3.7-max", "Qwen托管"), ModelInfo("gpt-5.4-mini", "OpenAI托管"),
            ModelInfo("kimi-k3", "Kimi托管"), ModelInfo("glm-5.2", "GLM托管"),
            ModelInfo("grok-4.5", "Grok托管"), ModelInfo("(更多模型见API返回)", "提示"))),
    // MiniMax (稀宇科技) — 官方 OpenAI SDK 文档 (platform.minimaxi.com, 2026-08-17 核对):
    // Bearer 认证; 默认 thinking 内联在 content 的 <think> 标签内 (响应侧剥离到 onReasoning);
    // M3 支持图片/视频输入, 官方明确"当前不支持音频输入"。显示顺序由
    // FrameworkSettingsContent 的 sortedBy(enLabel) 决定, 与声明位置无关。
    MINIMAX("MiniMax (稀宇科技)", "MiniMax", "https://api.minimaxi.com/v1/chat/completions", "MiniMax-M3", "",
        listOf(ModelInfo("MiniMax-M3", "旗舰·1M上下文"), ModelInfo("MiniMax-M2.7", "均衡"),
            ModelInfo("MiniMax-M2.7-highspeed", "极速"), ModelInfo("MiniMax-M2.5", "性价比"),
            ModelInfo("MiniMax-M2.5-highspeed", "极速"), ModelInfo("MiniMax-M2.1", "编程"),
            ModelInfo("MiniMax-M2.1-highspeed", "极速"), ModelInfo("MiniMax-M2", "编码/Agent"))),
    SELF_HOSTED("Self-Hosted (自建)", "Self-Hosted", "http://192.168.1.100:${com.mengpaw.kernel.ports.Ports.LLM_SELF}/v1/chat/completions", "local-model", "",
        listOf(ModelInfo("local-model", "Chat"), ModelInfo("qwen2.5:7b", "Chat"), ModelInfo("llama3.1:8b", "Chat"))),
    CUSTOM("Custom", "Custom", "", "", "", emptyList());

    companion object {
        /**
         * 框架设置预置供应商 chips 显示顺序 (v0.41.0): 除自建/自定义外按英文名首字母排序 —
         * 与枚举声明顺序解耦, 新增预置项无需调整声明位置。自建/自定义由调用方单独一行展示。
         */
        fun presetChipOrder(): List<LlmProviderPreset> =
            entries
                .filter { it != CUSTOM && it != SELF_HOSTED }
                .sortedBy { it.enLabel.lowercase() }
    }
}

fun LlmProviderPreset.modelListDisplay(): List<ModelInfo> =
    if (models.size <= 5) models else models.take(5)

/**
 * Settings state for the app.
 */
data class SavedProvider(
    val preset: LlmProviderPreset,
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val balance: String = ""
)

/** Agent language modes for controlling LLM output language. */
enum class AgentLanguageMode(val labelKey: String) { FOLLOW_UI("followUi"), CHINESE("chinese"), ENGLISH("english") }

/** Agent loop / execution mode. label/desc 中文 + enLabel/enDesc 英文（英文 UI 用）。 */
enum class LoopMode(val label: String, val desc: String, val enLabel: String, val enDesc: String) {
    REACT("React 模式", "标准问答，灵活高效",
        "React Mode", "Standard Q&A, flexible and efficient"),
    GOAL("Goal 模式", "单目标驱动，完成即停",
        "Goal Mode", "Single goal, stops when done"),
    // v0.34.3: 中文名带斜杠命令前缀 — 让 Loop 模式设置与 /Swarm /Fleet 直观关联
    // v0.34.4: Swarm 是进化版的 Mission — Mission 并入 Swarm，原 Mission 任务全部由 Swarm 负责
    SWARM("Swarm 火种模式",
        "星星之火，可以燎原：Swarm 是进化版的 Mission — 继承拆解→并行 Worker→验证→合成与降级通过，进化出角色混合模型、Andon 失败协议（重派/终止）与共享步数预算；原 Mission 任务由 Swarm 承接 (/Swarm)",
        "Swarm Mode",
        "The evolved Mission: inherits decompose→parallel workers→verify→synthesize with downgrade-pass; evolved role-based models, Andon failure protocol (redeploy/terminate) and shared step budget. Former Mission tasks are handled by Swarm (/Swarm)"),
    FLEET("Fleet 步坦协同模式", "装甲集群推进+步兵协同清剿：多 Agent 编队协同，跨设备分布式执行复杂任务 (/Fleet)",
        "Fleet Mode", "Multi-agent combined-arms coordination for distributed complex tasks (/Fleet)")
}

/** 主题模式 — 亮色 / 暗色 / 跟随系统。 */
enum class ThemeMode(val label: String, val enLabel: String) {
    LIGHT("亮色", "Light"),
    DARK("暗色", "Dark"),
    SYSTEM("跟随系统", "System")
}

/** 后台运行策略。 */
enum class BackgroundMode(val label: String, val desc: String, val enLabel: String, val enDesc: String) {
    NOTIFICATION("通知栏常驻", "状态栏显示图标，保活最强，推荐", "Persistent Notification", "Status bar icon, strongest keep-alive (recommended)"),
    SILENT("静默运行", "隐藏通知图标，前台服务仍在后台", "Silent Running", "Hidden notification icon, foreground service still active"),
    FOREGROUND_ONLY("仅前台使用", "退出时释放服务，最省电", "Foreground Only", "Service released on exit, most battery-efficient")
}

data class SettingsState(
    val selectedProvider: LlmProviderPreset = LlmProviderPreset.OPENAI,
    val apiEndpoint: String = LlmProviderPreset.OPENAI.endpoint,
    val apiKey: String = "",
    val modelName: String = LlmProviderPreset.OPENAI.defaultModel,
    val remoteModels: List<String> = emptyList(),
    val remoteModelsFetched: Boolean = false,
    val maxSteps: Int = 50,
    val llmMaxConcurrency: Int = 10,
    val commandTimeoutSec: Int = 60,
    val timezone: String = java.util.TimeZone.getDefault().id,
    val contextStrategy: String = "default",
    val memoryBackend: String = "builtin",
    // v0.35.1: 主题默认跟随系统 (此前默认亮色)
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showApiKey: Boolean = false,
    val backgroundMode: BackgroundMode = BackgroundMode.NOTIFICATION,
    /** 自动翻译(美系模型) — opt-in, 默认关闭, 用户主动开启才加载 Google 翻译. */
    val autoTranslate: Boolean = false,
    /** 后台省电偏好 — 持久化到 CONFIG/power_saver。目前为偏好记录（Agent 可经 self.config 读取），暂无主动节流实现。 */
    val powerSaverEnabled: Boolean = false,
    val useChinese: Boolean = true,
    val agentLanguageMode: AgentLanguageMode = AgentLanguageMode.FOLLOW_UI,
    val loopMode: LoopMode = LoopMode.REACT,
    // API section state
    val apiSectionExpanded: Boolean = false,
    val savedProviders: List<SavedProvider> = emptyList(),
    val isTesting: Boolean = false,
    val balance: String = "",
    /** 角色模型路由 — Fleet/火种各角色 → provider 快照（只配想覆盖的角色，缺省回退主模型）。 */
    val swarmRoles: Map<String, SavedProvider> = emptyMap()
) {
    val strings: AppStrings get() = if (useChinese) ChineseStrings else EnglishStrings

    /** Resolved Agent language: follow UI or user override. */
    val effectiveAgentLanguage: PromptEngine.AgentLanguage get() = when (agentLanguageMode) {
        AgentLanguageMode.FOLLOW_UI -> PromptEngine.AgentLanguage.fromUiChinese(useChinese)
        AgentLanguageMode.CHINESE -> PromptEngine.AgentLanguage.CHINESE
        AgentLanguageMode.ENGLISH -> PromptEngine.AgentLanguage.ENGLISH
    }
}
