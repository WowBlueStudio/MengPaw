// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * 孪生能力命令组 — 从 MemoryTwinPlugin 拆分。
 * capabilities / delegate / route (能力卡采集与任务路由)。
 *
 * 状态读写走 [TwinRuntimeState]; 全局依赖 (appContext/llmProvider/pluginNames/
 * agentSessionId/agentIsBusy) 读 [MemoryTwinPlugin] companion。
 */
internal class TwinCapabilityCommands(
    private val state: TwinRuntimeState
) {

    // ── Capability commands ───────────────────────────────────────

    suspend fun cmdCapabilities(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val context = MemoryTwinPlugin.appContext ?: return ExecutionResult.fail("无法获取设备上下文")
        val flag = args.getOrNull(0) ?: "--self"

        return when (flag) {
            "--self" -> {
                val collector = TwinCapabilityCollector(context, state.deviceId, state.deviceName,
                    mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION,
                    agentName = MemoryTwinPlugin.agentName)
                val card = collector.collect(MemoryTwinPlugin.llmProvider, MemoryTwinPlugin.pluginNames,
                    currentSessionId = MemoryTwinPlugin.agentSessionId,
                    isBusy = MemoryTwinPlugin.agentIsBusy)
                ExecutionResult.ok(card.toJson())
            }
            "--all" -> {
                // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
                if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
                val collector = TwinCapabilityCollector(context, state.deviceId, state.deviceName,
                    mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION,
                    agentName = MemoryTwinPlugin.agentName)
                val selfCard = collector.collect(MemoryTwinPlugin.llmProvider, MemoryTwinPlugin.pluginNames,
                    currentSessionId = MemoryTwinPlugin.agentSessionId,
                    isBusy = MemoryTwinPlugin.agentIsBusy)
                val peers = state.syncEngine.getPeers()
                val peerCards = peers.mapNotNull { peer ->
                    peer.capabilityCard?.let { CapabilityCard.fromJson(it) }
                }
                val sb = StringBuilder()
                sb.appendLine("## 设备能力对比")
                sb.appendLine()
                sb.appendLine("| 设备 | 形态 | 模型 | 上下文 | 摄像头 | 电池 |")
                sb.appendLine("|------|------|------|--------|--------|------|")
                sb.appendLine(capabilityRow(selfCard))
                peerCards.forEach { sb.appendLine(capabilityRow(it)) }
                sb.appendLine()
                sb.appendLine("> 使用 twin.route <任务> 获取任务路由推荐")
                ExecutionResult.ok(sb.toString())
            }
            else -> {
                // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
                if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
                val peers = state.syncEngine.getPeers()
                val peer = peers.find { it.peerId == flag || it.peerId.startsWith(flag) }
                val card = peer?.capabilityCard
                if (card != null) {
                    ExecutionResult.ok(card)
                } else {
                    ExecutionResult.fail("未找到该节点的能力卡: $flag。使用 twin.peers 查看所有节点。")
                }
            }
        }
    }

    private fun capabilityRow(card: CapabilityCard): String {
        val camera = if (card.hardware.hasCamera) "✓ ${card.hardware.cameraFacing.joinToString()}" else "✗"
        val battery = "${card.hardware.batteryLevel}%${if (card.hardware.isCharging) " ⚡" else ""}"
        // 2026-09-10: 能力值带上判定来源, 未知上下文不伪装成 0K (旧实现把"不知道"显示为 0K)
        val sourceTag = if (card.model.qualitySource == CapabilitySource.UNKNOWN) "?"
        else "(${card.model.qualitySource.shortLabel()})"
        val ctx = if (card.model.ctxKnown) "${card.model.contextWindowTokens / 1000}K" else "未知"
        return "| ${card.deviceName} | ${card.formFactor.name} | ${card.model.modelName}$sourceTag | $ctx | $camera | $battery |"
    }

    suspend fun cmdDelegate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: twin.delegate <peer-id> <task>")
        // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
        val peerId = args[0]
        val task = args.drop(1).joinToString(" ")
        val peers = state.syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId。使用 twin.peers 查看所有已知节点。")

        if (!com.mengpaw.kernel.security.PromptFirewall.isTrusted(peerId)) {
            return ExecutionResult.fail("未配对设备: $peerId。请先完成孪生配对（侧边栏 5 连击 MengPaw 框架图标）。")
        }

        val msg = com.mengpaw.kernel.acp.AcpMessage.twinDelegate(state.deviceId, peerId, task)
        val sent = MemoryTwinPlugin.acpTransport?.send(msg) ?: false
        return if (sent) {
            ExecutionResult.ok("任务已委派到 ${peer.agentName} ($peerId) — 使用 twin.status 查看状态")
        } else {
            ExecutionResult.fail("发送失败: 对端 ${peer.address}:${peer.port} 不可达。\n检查: 1) 对端是否在线 2) 网络是否互通 3) 防火墙是否拦截端口 ${com.mengpaw.kernel.ports.Ports.ACP}")
        }
    }

    suspend fun cmdRoute(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val task = args.joinToString(" ")
        if (task.isBlank()) return ExecutionResult.fail("用法: twin.route <任务描述>")
        // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")

        val context = MemoryTwinPlugin.appContext ?: return ExecutionResult.fail("无法获取设备上下文")
        val collector = TwinCapabilityCollector(context, state.deviceId, state.deviceName,
            mengpawVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION)
        val selfCard = collector.collect(MemoryTwinPlugin.llmProvider, MemoryTwinPlugin.pluginNames,
            currentSessionId = MemoryTwinPlugin.agentSessionId,
            isBusy = MemoryTwinPlugin.agentIsBusy)
        val peers = state.syncEngine.getPeers()
        val peerCards = peers.mapNotNull { peer ->
            peer.capabilityCard?.let { CapabilityCard.fromJson(it) }
        }

        val analysis = TwinRouter.route(task, selfCard, peerCards)
        return ExecutionResult.ok(analysis.summary)
    }

    // ── Model capability evolution (2026-09-10) ───────────────────

    /**
     * `twin.model` — 模型能力画像与"用中学"入口。
     *
     * 子命令: (无)/show 画像 | rules 生效规则 | evidence 实测证据 |
     *         observe <fact> [value] 记录实测事实 | reset 清空证据
     */
    suspend fun cmdModel(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agentName = MemoryTwinPlugin.agentName
        return when (val sub = (args.getOrNull(0) ?: "show").lowercase()) {
            "show", "" -> ExecutionResult.ok(renderProfile(agentName))
            "rules" -> ExecutionResult.ok(
                ModelCapabilityRules.describeAll(ModelCapabilityRules.loadExternalCached(agentName)) +
                    "\n规则文件: ${ModelCapabilityRules.rulesFilePath(agentName).absolutePath}"
            )
            "evidence" -> ExecutionResult.ok(renderEvidence())
            "observe" -> observeFact(args.drop(1), agentName)
            "reset" -> {
                val n = ModelEvidenceStore.clearAll()
                ExecutionResult.ok("已清空 $n 条模型实测证据 — 判定回到规则层。")
            }
            else -> ExecutionResult.fail(
                "用法: twin.model [show|rules|evidence|observe <fact> [value]|reset]\n" +
                    "事实: vision_ok / vision_rejected / tool_ok / tool_rejected / " +
                    "context_ok / context_overflow / success / failure"
            )
        }
    }

    private fun renderProfile(agentName: String): String {
        val provider = MemoryTwinPlugin.llmProvider
            ?: return "未配置 LLM provider — 无法生成模型画像。"
        val profile = ModelProfileResolver.resolve(provider, agentName)
        val file = ModelCapabilityRules.rulesFilePath(agentName)
        return buildString {
            appendLine("## 模型能力画像")
            appendLine()
            appendLine("- 模型: ${profile.modelName} (provider=${profile.providerName}, 类型=${profile.providerType})")
            appendLine("- 档位: ${profile.estimatedQuality}${sourceTag(profile.qualitySource)}")
            appendLine("- 上下文: ${if (profile.ctxKnown) "${profile.contextWindowTokens / 1000}K" else "未知"}${sourceTag(profile.ctxSource)}")
            appendLine("- 视觉: ${visionLabel(profile)}${sourceTag(profile.visionSource)}")
            appendLine("- 工具调用: ${if (profile.toolsSource == CapabilitySource.UNKNOWN) "未知" else if (profile.supportsTools) "支持" else "不支持"}${sourceTag(profile.toolsSource)}")
            appendLine("- 实测证据: ${profile.evidenceCount} 条")
            profile.matchedRuleId?.let { appendLine("- 命中规则: $it") }
            appendLine("- 外置规则文件: ${file.absolutePath} ${if (file.exists()) "(已生效)" else "(不存在 — 创建即可登记新模型, 无需改代码发版)"}")
            appendLine()
            appendLine("> 判定优先级: 实测证据 > 外置规则 > 内置族级规则 > 档位推断 > 未知(中性不扣分)")
            appendLine("> 用 `twin.model observe <fact>` 把一次真实使用结果记成证据, 判定立即跟着修正。")
        }
    }

    private fun renderEvidence(): String {
        val all = ModelEvidenceStore.snapshot()
        if (all.isEmpty()) {
            return "尚无实测证据 — 用 `twin.model observe <fact>` 记录一次真实结果, 之后判定优先听证据。"
        }
        return buildString {
            appendLine("## 模型实测证据 (${all.size} 条)")
            appendLine()
            appendLine("| 模型 | provider | 视觉 ✓/✗ | 工具 ✓/✗ | 上下文 实测/溢出 | 成败 |")
            appendLine("|------|----------|-----------|-----------|------------------|------|")
            all.forEach {
                appendLine(
                    "| ${it.model} | ${it.provider} | ${it.visionOk}/${it.visionRejected} | " +
                        "${it.toolsOk}/${it.toolsRejected} | ${it.maxObservedContext / 1000}K/${it.ctxOverflowAt / 1000}K | " +
                        "${it.successCount}/${it.failureCount} |"
                )
            }
            appendLine()
            appendLine("证据文件: ${ModelEvidenceStore.filePath().absolutePath} (本机积累, 经能力卡共享给对端)")
        }
    }

    private fun observeFact(rest: List<String>, agentName: String): ExecutionResult {
        val rawFact = rest.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.model observe <fact> [value] [--tokens N]")
        val fact = parseFact(rawFact)
            ?: return ExecutionResult.fail("未知事实: $rawFact (可用: vision_ok/vision_rejected/tool_ok/tool_rejected/context_ok/context_overflow/success/failure)")
        val value = rest.drop(1).firstOrNull { it.toIntOrNull() != null }?.toIntOrNull() ?: 0
        val provider = MemoryTwinPlugin.llmProvider
            ?: return ExecutionResult.fail("未配置 LLM provider — 无当前模型可记录。")
        val info = try {
            provider.info()
        } catch (e: Exception) {
            return ExecutionResult.fail("读取当前 provider 失败: ${e.message}")
        }

        val before = ModelProfileResolver.resolve(provider, agentName)
        val evidence = ModelEvidenceStore.record(info.model, info.name, fact, value)
        val after = ModelProfileResolver.resolve(provider, agentName)

        return ExecutionResult.ok(buildString {
            appendLine("已记录实测事实: ${fact.name} (模型 ${info.model}${if (value > 0) ", value=$value" else ""})")
            appendLine("- 证据累计: ${evidence.factCount} 条")
            if (before.supportsVision != after.supportsVision || before.visionSource != after.visionSource) {
                appendLine("- 视觉判定: ${visionLabel(before)} → ${visionLabel(after)}")
            }
            if (before.contextWindowTokens != after.contextWindowTokens) {
                appendLine("- 上下文: ${before.contextWindowTokens / 1000}K → ${after.contextWindowTokens / 1000}K")
            }
            if (before.supportsTools != after.supportsTools) {
                appendLine("- 工具判定: ${before.supportsTools} → ${after.supportsTools}")
            }
            appendLine()
            appendLine("> 下次 twin.route 按新证据打分 (实测 > 声明)。")
        })
    }

    private fun parseFact(raw: String): EvidenceFact? = when (raw.lowercase().replace('-', '_')) {
        "vision_ok", "vision" -> EvidenceFact.VISION_OK
        "vision_rejected", "no_vision", "vision_fail" -> EvidenceFact.VISION_REJECTED
        "tool_ok", "tools_ok" -> EvidenceFact.TOOL_OK
        "tool_rejected", "tools_rejected" -> EvidenceFact.TOOL_REJECTED
        "context_ok" -> EvidenceFact.CONTEXT_OK
        "context_overflow", "overflow" -> EvidenceFact.CONTEXT_OVERFLOW
        "success" -> EvidenceFact.SUCCESS
        "failure", "fail" -> EvidenceFact.FAILURE
        else -> null
    }

    private fun visionLabel(profile: ModelProfile): String = when {
        profile.visionSource == CapabilitySource.UNKNOWN -> "未知"
        profile.supportsVision -> "支持"
        else -> "不支持"
    }

    private fun sourceTag(source: CapabilitySource): String =
        if (source == CapabilitySource.UNKNOWN) "" else " [${source.shortLabel()}]"
}
