// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens.model

/**
 * 模型语音（音频输入）能力判定 (v0.33.0+)。
 *
 * 策略: 内置精确前缀清单 + 名称关键词兜底 — 自定义模型名命中关键词即识别, 零配置。
 * 不支持语音的模型不显示语音按钮 — 用户可用 Android 输入法自带的语音转译输入。
 *
 * 刻意排除 `gemini`: OpenAI 兼容代理对 input_audio 的翻译不可靠, 误判会导致 400;
 * 如需支持在 [KNOWN_PREFIXES] 加一行精确前缀。
 */
object VoiceCapability {
    /** 精确前缀清单（大小写不敏感, 2026-08 支持音频输入的常见模型）。 */
    private val KNOWN_PREFIXES = listOf(
        "gpt-5", "gpt-4o",                    // OpenAI: gpt-4o 起原生音频输入, gpt-5 全系
        "qwen3-omni", "qwen2.5-omni", "qwen-omni",  // 通义千问全模态
        "glm-4.5v", "glm-5v",                 // 智谱多模态（音视频理解）
        "doubao-1.5-audio", "doubao-audio"    // 豆包语音理解
    )

    /** 关键词兜底 — 自定义模型名命中即识别。 */
    private val KEYWORDS = listOf("omni", "audio", "voice", "whisper", "speech")

    fun supportsVoice(modelName: String): Boolean {
        val name = modelName.trim().lowercase()
        if (name.isEmpty()) return false
        if (KNOWN_PREFIXES.any { name.startsWith(it) }) return true
        return KEYWORDS.any { name.contains(it) }
    }

    /** 结合模型展示类型（ModelInfo.type）判定 — "全模态" 兜底。 */
    fun supportsVoice(modelName: String, modelType: String): Boolean =
        supportsVoice(modelName) || modelType.contains("全模态")
}
