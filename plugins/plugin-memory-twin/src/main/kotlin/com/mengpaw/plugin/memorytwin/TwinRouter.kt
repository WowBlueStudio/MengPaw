// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

/**
 * Capability-aware task router — helps the MengPaw Twin Agent decide
 * which device ("body" + "brain") is best suited for a given task.
 *
 * The router scores each available twin peer against the task's inferred
 * requirements and returns a ranked recommendation.
 */
object TwinRouter {

    /** A routing recommendation for a specific peer. */
    data class RouteRecommendation(
        val peerId: String,
        val deviceName: String,
        val formFactor: String,
        val modelName: String,
        val score: Int,
        val strengths: List<String>,
        val weaknesses: List<String>,
        val verdict: String // one-line recommendation
    )

    /** Result of a task route analysis. */
    data class RouteAnalysis(
        val task: String,
        val inferredRequirements: List<String>,
        val recommendations: List<RouteRecommendation>,
        val bestPeerId: String?,
        val summary: String
    )

    /**
     * Analyze a task and recommend the best device to execute it.
     *
     * @param task The task description (自然语言)
     * @param selfCard Our own capability card
     * @param peerCards Capability cards of all known twin peers
     */
    fun route(
        task: String,
        selfCard: CapabilityCard,
        peerCards: List<CapabilityCard>
    ): RouteAnalysis {
        val requirements = inferRequirements(task)
        val allCards = listOf(selfCard) + peerCards
        val scored = allCards.map { card ->
            scorePeer(card, requirements)
        }.sortedByDescending { it.score }

        val best = scored.firstOrNull()
        return RouteAnalysis(
            task = task,
            inferredRequirements = requirements,
            recommendations = scored,
            bestPeerId = best?.peerId,
            summary = buildSummary(scored, requirements)
        )
    }

    // ── Requirement inference ──────────────────────────────────────

    /**
     * Infer task requirements from natural language description.
     *
     * Simple keyword/pattern matching — adequate for the agent to make
     * routing decisions without an extra LLM call. The agent can always
     * override with explicit requirements in twin.delegate.
     */
    private fun inferRequirements(task: String): List<String> {
        val reqs = mutableListOf<String>()
        val lower = task.lowercase()

        // Vision tasks
        if (Regex("""拍照|摄像|照片|图片|图像|视觉|识别|看|扫描|OCR|二维码|扫一扫""").containsMatchIn(task)) {
            reqs.add("hardware:camera")
            reqs.add("model:vision")
        }
        // Audio tasks
        if (Regex("""录音|语音|听写|音频|播放音乐|听歌""").containsMatchIn(task)) {
            reqs.add("hardware:audio")
        }
        // Location tasks
        if (Regex("""定位|导航|地图|GPS|经纬度|在哪里|附近""").containsMatchIn(task)) {
            reqs.add("hardware:gps")
        }
        // Large context tasks
        if (Regex("""分析.*(PDF|文档|报告|论文|书)|长文|长篇|全文|整本""").containsMatchIn(task)) {
            reqs.add("context:large")
        }
        // Strong reasoning
        if (Regex("""推理|逻辑|论证|分析.*(深入|深度|复杂)|code review|代码审查|架构""").containsMatchIn(task)) {
            reqs.add("reasoning:high")
        }
        // Browser tasks
        if (Regex("""网页|浏览器|打开.*(链接|URL|网站)|搜索.*(网上|互联网)""").containsMatchIn(task)) {
            reqs.add("software:browser")
        }
        // Big screen tasks
        if (Regex("""投屏|幻灯片|演示|展示|大屏|播放.*(视频|电影)""").containsMatchIn(task)) {
            reqs.add("hardware:bigscreen")
        }
        // SMS/Telephony
        if (Regex("""短信|电话|拨号|发.*消息|通知""").containsMatchIn(task)) {
            reqs.add("hardware:telephony")
        }
        // Local file operations
        if (Regex("""文件|存储|下载|备份|清理|整理.*(照片|文件)""").containsMatchIn(task)) {
            reqs.add("software:filesystem")
        }
        // Heavy computation
        if (Regex("""训练|模型|编译|构建|build|gradle|maven|镜像""").containsMatchIn(task)) {
            reqs.add("hardware:compute")
        }

        return reqs.distinct()
    }

    // ── Peer scoring ──────────────────────────────────────────────

    private fun scorePeer(card: CapabilityCard, requirements: List<String>): RouteRecommendation {
        var score = 50 // baseline
        val strengths = mutableListOf<String>()
        val weaknesses = mutableListOf<String>()

        requirements.forEach { req ->
            when {
                req == "hardware:camera" -> {
                    if (card.hardware.hasCamera) { score += 15; strengths.add("有摄像头(${card.hardware.cameraFacing.joinToString()})") }
                    else { score -= 10; weaknesses.add("无摄像头") }
                }
                req == "model:vision" -> {
                    if (card.model.supportsVision) { score += 15; strengths.add("视觉模型:${card.model.modelName}") }
                    else { score -= 5; weaknesses.add("模型不支持视觉") }
                }
                req == "hardware:audio" -> {
                    score += 5; strengths.add("音频支持")
                }
                req == "hardware:gps" -> {
                    score += 5; strengths.add("GPS定位")
                }
                req == "context:large" -> {
                    if (card.model.contextWindowTokens >= 64_000) { score += 20; strengths.add("大上下文:${card.model.contextWindowTokens / 1000}K") }
                    else { score -= 10; weaknesses.add("上下文较小:${card.model.contextWindowTokens / 1000}K") }
                }
                req == "reasoning:high" -> {
                    when (card.model.estimatedQuality) {
                        ModelQuality.HIGH -> { score += 20; strengths.add("强推理模型:${card.model.modelName}") }
                        ModelQuality.MEDIUM -> { score += 5; strengths.add("中等推理:${card.model.modelName}") }
                        else -> { score -= 10; weaknesses.add("推理能力较弱") }
                    }
                }
                req == "software:browser" -> {
                    if (card.software.optionalCapabilities.contains("browser")) { score += 10; strengths.add("浏览器可用") }
                    else { score -= 5; weaknesses.add("无浏览器") }
                }
                req == "hardware:bigscreen" -> {
                    if (card.formFactor == FormFactor.TABLET || card.formFactor == FormFactor.TV) {
                        score += 10; strengths.add("大屏设备")
                    } else { score -= 3 }
                }
                req == "hardware:telephony" -> {
                    if (card.formFactor == FormFactor.PHONE) { score += 10; strengths.add("手机通讯") }
                    else { score -= 5; weaknesses.add("非手机设备") }
                }
                req == "software:filesystem" -> {
                    if (card.software.installedPlugins.any { it.contains("fs") }) { score += 5; strengths.add("文件系统") }
                }
                req == "hardware:compute" -> {
                    if (card.hardware.cpuCores >= 8 && card.hardware.ramTotalMB >= 4096) { score += 10; strengths.add("强算力") }
                }
            }
        }

        // Form factor bonuses
        when (card.formFactor) {
            FormFactor.PHONE -> { if (requirements.none { it.startsWith("hardware:") }) score += 5 } // general tasks
            FormFactor.TABLET -> { score += 3 } // good for most things
            FormFactor.TV -> { score -= 5; if (requirements.any { it == "hardware:bigscreen" }) score += 10 }
            FormFactor.WEAR -> { score -= 15; weaknesses.add("手表:能力受限") }
            else -> {}
        }

        // Battery penalty
        if (card.hardware.batteryLevel in 1..15 && !card.hardware.isCharging) {
            score -= 15; weaknesses.add("电量低:${card.hardware.batteryLevel}%")
        }

        // Network penalty
        if (card.hardware.networkType == "Mobile") {
            score -= 3; weaknesses.add("移动网络")
        }

        return RouteRecommendation(
            peerId = card.deviceId,
            deviceName = card.deviceName,
            formFactor = card.formFactor.name,
            modelName = card.model.modelName,
            score = score.coerceIn(0, 100),
            strengths = strengths,
            weaknesses = weaknesses,
            verdict = when {
                score >= 80 -> "🌟 强烈推荐 — 最适合此任务"
                score >= 60 -> "✅ 可以胜任"
                score >= 40 -> "⚠️ 可以尝试,但有不足"
                else -> "❌ 不建议使用此设备"
            }
        )
    }

    // ── Summary builder ───────────────────────────────────────────

    private fun buildSummary(
        recommendations: List<RouteRecommendation>,
        requirements: List<String>
    ): String = buildString {
        appendLine("## 任务路由分析")
        appendLine()
        if (requirements.isNotEmpty()) {
            appendLine("**推断需求**: ${requirements.joinToString(", ")}")
        } else {
            appendLine("**推断需求**: 无特殊要求,通用任务")
        }
        appendLine()
        appendLine("| 排名 | 设备 | 形态 | 模型 | 评分 | 结论 |")
        appendLine("|------|------|------|------|------|------|")
        recommendations.take(5).forEachIndexed { i, r ->
            val medal = when (i) { 0 -> "🥇" 1 -> "🥈" 2 -> "🥉" else -> "${i + 1}" }
            appendLine("| $medal | ${r.deviceName} | ${r.formFactor} | ${r.modelName} | ${r.score} | ${r.verdict} |")
        }
        appendLine()
        val best = recommendations.firstOrNull()
        if (best != null) {
            appendLine("**推荐**: 在 **${best.deviceName}** 上执行此任务")
            if (best.strengths.isNotEmpty()) {
                appendLine("- 优势: ${best.strengths.joinToString("; ")}")
            }
            if (best.weaknesses.isNotEmpty()) {
                appendLine("- 不足: ${best.weaknesses.joinToString("; ")}")
            }
        }
    }
}
