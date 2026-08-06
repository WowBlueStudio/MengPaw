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
// 命名避开同名冲突 — 各对话框文件已有自己的 private appJson (AddFrameworkDialog/FrameworkCardDialog)。

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
    // ═══ Presets verified against official docs — 2026-07-21 ═══
    // Only top models listed here; full list fetched from API on key entry.
    OPENAI("OpenAI", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5.4", "sk-",
        listOf(ModelInfo("gpt-5.4", "旗舰"), ModelInfo("gpt-5.4-mini", "快速"), ModelInfo("gpt-5.4-nano", "轻量"),
            ModelInfo("gpt-5", "前代"), ModelInfo("o4-mini", "思维链"))),
    DEEPSEEK("DeepSeek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-v4-flash", "sk-",
        listOf(ModelInfo("deepseek-v4-flash", "快速"), ModelInfo("deepseek-v4-pro", "思维链"))),
    KIMI("Kimi (月之暗面)", "Kimi (Moonshot)", "https://api.moonshot.cn/v1/chat/completions", "kimi-k3", "sk-",
        listOf(ModelInfo("kimi-k3", "旗舰·1M上下文"), ModelInfo("kimi-k2.7-code", "Coding"),
            ModelInfo("kimi-k2.6", "通用"), ModelInfo("kimi-k2.7-code-highspeed", "高速Coding"))),
    GLM("GLM (智谱)", "GLM (Zhipu)", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-5.2", "",
        listOf(ModelInfo("glm-5.2", "旗舰·1M上下文"), ModelInfo("glm-5.1", "Coding"),
            ModelInfo("glm-5", "前代"), ModelInfo("glm-5-turbo", "高速"), ModelInfo("glm-5v-turbo", "多模态"))),
    QWEN("DashScope", "DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3.7-max", "sk-",
        listOf(ModelInfo("qwen3.7-max", "旗舰·1M上下文"), ModelInfo("qwen3.6-35b-a3b", "开源MoE"),
            ModelInfo("qwen3.5-plus", "均衡"), ModelInfo("qwen-flash", "快速"),
            ModelInfo("qwen3-coder-plus", "Coding"), ModelInfo("qwq-plus", "思维链"),
            ModelInfo("qwen3-vl-plus", "多模态"), ModelInfo("qwen3-omni-flash", "全模态"))),
    GROK("Grok (xAI)", "Grok (xAI)", "https://api.x.ai/v1/chat/completions", "grok-4.3", "xai-",
        listOf(ModelInfo("grok-4.5", "旗舰"), ModelInfo("grok-4.3", "推荐·1M上下文"),
            ModelInfo("grok-4.20-reasoning", "思维链"), ModelInfo("grok-4.1-fast-non-reasoning", "快速"),
            ModelInfo("grok-build-0.1", "Coding"))),
    VOLCANO("火山引擎 (豆包)", "Volcano Engine (Doubao)", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-seed-2.0-pro", "",
        listOf(ModelInfo("doubao-seed-2.0-pro", "旗舰"), ModelInfo("doubao-seed-2.0-lite", "均衡"),
            ModelInfo("doubao-seed-2.0-mini", "轻量"), ModelInfo("doubao-seed-1.8", "前代"),
            ModelInfo("doubao-seed-1.6-flash", "快速"), ModelInfo("doubao-seed-1.6-thinking", "思维链"),
            ModelInfo("deepseek-v3-2", "DeepSeek托管"), ModelInfo("glm-4.7", "GLM托管"),
            ModelInfo("(需创建接入点 ep-xxx)", "提示"))),
    OPENMODEL("OpenModel", "OpenModel", "https://api.openmodel.ai/v1/chat/completions", "deepseek-v4-flash", "sk-",
        listOf(ModelInfo("deepseek-v4-pro", "思维链"), ModelInfo("deepseek-v4-flash", "快速"),
            ModelInfo("qwen3.7-max", "Qwen托管"), ModelInfo("gpt-5.4-mini", "OpenAI托管"),
            ModelInfo("kimi-k3", "Kimi托管"), ModelInfo("glm-5.2", "GLM托管"),
            ModelInfo("grok-4.5", "Grok托管"), ModelInfo("(更多模型见API返回)", "提示"))),
    SELF_HOSTED("Self-Hosted (自建)", "Self-Hosted", "http://192.168.1.100:${com.mengpaw.kernel.ports.Ports.LLM_SELF}/v1/chat/completions", "local-model", "",
        listOf(ModelInfo("local-model", "Chat"), ModelInfo("qwen2.5:7b", "Chat"), ModelInfo("llama3.1:8b", "Chat"))),
    CUSTOM("Custom", "Custom", "", "", "", emptyList());
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
    MISSION("Mission 模式", "建立临时子 Agent 分解任务链，逐步执行",
        "Mission Mode", "Decompose into subtask chain with temporary sub-agents"),
    SWARM("火种模式", "星星之火，可以燎原：并行 Worker 协作，可按角色混合模型",
        "Swarm Mode", "Parallel workers with role-based model routing"),
    FLEET("步坦协同模式", "装甲集群推进+步兵协同清剿：多 Agent 编队协同，跨设备分布式执行复杂任务",
        "Combined Arms Mode", "Multi-agent combined-arms coordination for distributed complex tasks")
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
    val themeMode: ThemeMode = ThemeMode.LIGHT,
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
