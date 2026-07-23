// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
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

    // Expand panel
    val expandFileSection: String,
    val expandModeSection: String,
    val expandPluginSection: String,
    val filePickImage: String,
    val filePickDocument: String,
    val filePickFile: String,
    val filePickCamera: String,
    val dragHint: String,

    // Execution mode tags
    val tagModeMission: String,
    val tagModeResearch: String,
    val tagModeTranslate: String,
    val tagModeSilent: String,
    val tagDismiss: String,
    val mentionHint: String,

    // Mode-specific placeholders
    val placeholderMission: String,
    val placeholderResearch: String,
    val placeholderTranslate: String,
    val placeholderSilent: String,

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
    darkThemeDesc = "亮色 / 暗色 / 跟随系统",
    language = "Language",
    languageDesc = "Switch UI language",
    languageZh = "中文",
    languageEn = "English",

    agentLanguage = "Agent Language",
    agentLanguageDesc = "Language for Agent's thinking and responses",
    agentLanguageFollowUi = "Follow UI",
    agentLanguageChinese = "中文",
    agentLanguageEnglish = "English",

    expandFileSection = "File Upload",
    expandModeSection = "Execution Mode",
    expandPluginSection = "Plugin Tools",
    filePickImage = "Image",
    filePickDocument = "Document",
    filePickFile = "File",
    filePickCamera = "Camera",
    dragHint = "Long-press to reorder",

    tagModeMission = "/Mission",
    tagModeResearch = "/Research",
    tagModeTranslate = "/Translate",
    tagModeSilent = "/Silent",
    tagDismiss = "Remove",
    mentionHint = "@mention an agent...",

    placeholderMission = "Describe a complex task for sub-agents to decompose...",
    placeholderResearch = "Enter a topic for deep research with source verification...",
    placeholderTranslate = "Enter text to translate...",
    placeholderSilent = "Describe a background task to run silently...",

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
    darkThemeDesc = "亮色 / 暗色 / 跟随系统",
    language = "语言 (Language)",
    languageDesc = "切换界面语言",
    languageZh = "中文",
    languageEn = "English",

    agentLanguage = "Agent 语言",
    agentLanguageDesc = "控制 Agent 思考和输出的语言",
    agentLanguageFollowUi = "跟随界面",
    agentLanguageChinese = "中文",
    agentLanguageEnglish = "English",

    expandFileSection = "文件提交",
    expandModeSection = "执行模式",
    expandPluginSection = "插件工具",
    filePickImage = "图片",
    filePickDocument = "文档",
    filePickFile = "文件",
    filePickCamera = "拍照",
    dragHint = "长按拖拽排序",

    tagModeMission = "/Mission",
    tagModeResearch = "/Research",
    tagModeTranslate = "/Translate",
    tagModeSilent = "/Silent",
    tagDismiss = "移除",
    mentionHint = "@智能体名称...",

    placeholderMission = "输入一个需要子智能体进行拆解执行的复杂任务",
    placeholderResearch = "输入需要深度调研的课题，将进行多轮搜索与交叉验证",
    placeholderTranslate = "输入需要翻译的内容",
    placeholderSilent = "输入后台静默执行的任务，完成后推送结果",

    about = "关于",
    version = "版本",
    core = "内核 (Core)",
    design = "设计 (Design)",
    resetDefaults = "恢复默认设置"
)
