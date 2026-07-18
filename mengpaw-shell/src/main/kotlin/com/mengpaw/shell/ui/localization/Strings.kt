// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.localization

/**
 * Localized strings for the MengPaw UI.
 * Switch between Chinese (zh) and English (en) with one click.
 */
data class AppStrings(
    // App
    val appName: String,
    val settings: String,
    val back: String,

    // Main Screen
    val systemBanner: String,
    val inputPlaceholder: String,
    val thinking: String,
    val agentLabel: String,
    val stop: String,
    val send: String,

    // Settings - LLM Provider
    val llmProvider: String,
    val useSimulated: String,
    val useSimulatedDesc: String,
    val apiEndpoint: String,
    val apiKey: String,
    val apiKeyShow: String,
    val apiKeyHide: String,
    val model: String,

    // Settings - Agent
    val agent: String,
    val maxSteps: String,
    val maxStepsDesc: String,

    // Settings - Appearance
    val appearance: String,
    val darkTheme: String,
    val darkThemeDesc: String,
    val language: String,
    val languageDesc: String,
    val languageZh: String,
    val languageEn: String,

    // Settings - Agent Language
    val agentLanguage: String,
    val agentLanguageDesc: String,
    val agentLanguageFollowUi: String,
    val agentLanguageChinese: String,
    val agentLanguageEnglish: String,

    // Settings - About
    val about: String,
    val version: String,
    val core: String,
    val design: String,
    val resetDefaults: String
)

/**
 * English strings (default).
 */
val EnglishStrings = AppStrings(
    appName = "MengPaw",
    settings = "Settings",
    back = "Back",

    systemBanner = "Agent is ready. Describe the task you want to accomplish.",
    inputPlaceholder = "Describe a task for the Agent...",
    thinking = "🤔 Thinking...",
    agentLabel = "Agent",
    stop = "Stop",
    send = "Send",

    llmProvider = "LLM Provider",
    useSimulated = "Use Simulated Provider",
    useSimulatedDesc = "Simulated responses for testing (no API key needed)",
    apiEndpoint = "API Endpoint",
    apiKey = "API Key",
    apiKeyShow = "Show",
    apiKeyHide = "Hide",
    model = "Model",

    agent = "Agent",
    maxSteps = "Max Steps",
    maxStepsDesc = "Maximum ReAct iterations before forced stop",

    appearance = "Appearance",
    darkTheme = "Dark Theme",
    darkThemeDesc = "Switch between light and dark color scheme",
    language = "Language",
    languageDesc = "Switch UI language",
    languageZh = "中文",
    languageEn = "English",

    agentLanguage = "Agent Language",
    agentLanguageDesc = "Language for Agent's thinking and responses",
    agentLanguageFollowUi = "Follow UI",
    agentLanguageChinese = "中文",
    agentLanguageEnglish = "English",

    about = "About",
    version = "Version",
    core = "Core",
    design = "Design",
    resetDefaults = "Reset to Defaults"
)

/**
 * Chinese (Simplified) strings.
 */
val ChineseStrings = AppStrings(
    appName = "MengPaw",
    settings = "设置 (Settings)",
    back = "返回",

    systemBanner = "Agent 已就绪。描述你想要完成的任务。",
    inputPlaceholder = "描述一个任务给 Agent...",
    thinking = "🤔 思考中 (Thinking)...",
    agentLabel = "Agent",
    stop = "停止",
    send = "发送",

    llmProvider = "LLM 提供商 (Provider)",
    useSimulated = "使用模拟服务 (Simulated)",
    useSimulatedDesc = "使用内置模拟响应进行测试（无需 API Key）",
    apiEndpoint = "API 地址 (Endpoint)",
    apiKey = "API 密钥 (Key)",
    apiKeyShow = "显示",
    apiKeyHide = "隐藏",
    model = "模型 (Model)",

    agent = "Agent",
    maxSteps = "最大步数 (Max Steps)",
    maxStepsDesc = "ReAct 循环最大迭代次数",

    appearance = "外观",
    darkTheme = "深色主题 (Dark Theme)",
    darkThemeDesc = "切换浅色/深色配色方案",
    language = "语言 (Language)",
    languageDesc = "切换界面语言",
    languageZh = "中文",
    languageEn = "English",

    agentLanguage = "Agent 语言",
    agentLanguageDesc = "控制 Agent 思考和输出的语言",
    agentLanguageFollowUi = "跟随界面",
    agentLanguageChinese = "中文",
    agentLanguageEnglish = "English",

    about = "关于",
    version = "版本",
    core = "内核 (Core)",
    design = "设计 (Design)",
    resetDefaults = "恢复默认设置"
)
