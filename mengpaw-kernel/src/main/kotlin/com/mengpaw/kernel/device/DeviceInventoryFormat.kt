// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

/**
 * md + frontmatter 编解码 (无第三方 YAML 依赖) — 自 [DeviceInventory] 拆出 (文件行数限制)。
 *
 * 容错口径 (§7.2 交付要求):
 * - 未知键 → [DeviceInventory.extra] 原样保留;
 * - 缺失键 → 默认值;
 * - 坏 frontmatter (无 `---` 包裹 / 顶层行无冒号 / 数值写成非数字 / `agents` 项无 `name`) → null 不抛。
 */
internal object DeviceInventoryFormat {

    private const val DELIM = "---"

    fun parse(text: String): DeviceInventory? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
        val lines = normalized.split('\n')
        if (lines.firstOrNull()?.trim() != DELIM) return null
        val end = lines.withIndex().drop(1).firstOrNull { it.value.trim() == DELIM }?.index ?: return null
        val fm = lines.subList(1, end)
        val body = lines.drop(end + 1).joinToString("\n").trim('\n')

        var deviceId = ""
        var legacy = ""
        var name = ""
        var form = DeviceInventory.FORM_UNKNOWN
        var os = ""
        var cpu = 0
        var ram = 0L
        var storage = 0L
        var screen = ""
        var sensors = emptyList<String>()
        var camera = emptyList<String>()
        var observedAt = ""
        var schemaVersion = DeviceInventory.SCHEMA_VERSION
        var status = DeviceInventory.STATUS_ACTIVE
        val extra = LinkedHashMap<String, String>()
        val agents = mutableListOf<AgentEntry>()

        // agents 块状态: inAgents = 已进入 agents 块; buf = 当前累积项 (null = 尚未开始下一项)
        var inAgents = false
        var agentBuf: LinkedHashMap<String, String>? = null
        var agentPlugins: List<String> = emptyList()
        var agentSources: Map<String, String> = emptyMap()

        fun flushAgent(): Boolean {
            val buf = agentBuf ?: return true
            val agentName = buf["name"].orEmpty()
            if (agentName.isBlank()) return false
            val ctx = buf["contextTokens"]
            if (ctx != null && ctx.toIntOrNull() == null) return false
            val vision = buf["vision"]
            if (vision != null && boolOrNull(vision) == null) return false
            val tools = buf["tools"]
            if (tools != null && boolOrNull(tools) == null) return false
            agents.add(
                AgentEntry(
                    name = agentName,
                    model = buf["model"].orEmpty(),
                    contextTokens = ctx?.toIntOrNull() ?: 0,
                    vision = vision?.let { boolOrNull(it) },
                    tools = tools?.let { boolOrNull(it) },
                    plugins = agentPlugins,
                    observedAt = buf["observedAt"].orEmpty(),
                    sources = agentSources,
                    extra = buf.filterKeys { it !in AGENT_KEYS }
                )
            )
            agentBuf = null
            agentPlugins = emptyList()
            agentSources = emptyMap()
            return true
        }

        fun readAgentKey(key: String, value: String, target: LinkedHashMap<String, String>) {
            when (key) {
                "plugins" -> agentPlugins = flowList(unquote(stripComment(value)))
                "sources" -> agentSources = flowMap(unquote(stripComment(value)))
                else -> target[key] = unquote(stripComment(value))
            }
        }

        for (raw in fm) {
            if (raw.isBlank()) continue
            val indented = raw.first().isWhitespace()
            val line = raw.trim()
            if (indented) {
                if (!inAgents) return null
                if (line.startsWith("- ")) {
                    if (!flushAgent()) return null
                    val rest = line.removePrefix("- ")
                    val newBuf = LinkedHashMap<String, String>()
                    agentBuf = newBuf
                    val key = rest.substringBefore(':', "").trim()
                    if (key.isEmpty() || !rest.contains(':')) return null
                    readAgentKey(key, rest.substringAfter(':'), newBuf)
                    continue
                }
                val buf = agentBuf ?: return null
                val key = line.substringBefore(':', "").trim()
                if (key.isEmpty() || !line.contains(':')) return null
                readAgentKey(key, line.substringAfter(':'), buf)
                continue
            }
            // ── 顶层键 ──
            val key = line.substringBefore(':', "").trim()
            if (key.isEmpty() || !line.contains(':')) return null
            if (inAgents) {
                if (!flushAgent()) return null
                inAgents = false
            }
            val rawValue = line.substringAfter(':')
            when (key) {
                "deviceId" -> deviceId = unquote(stripComment(rawValue))
                "legacyFingerprint" -> legacy = unquote(stripComment(rawValue))
                "deviceName" -> name = unquote(stripComment(rawValue))
                "formFactor" -> form = unquote(stripComment(rawValue)).ifBlank { DeviceInventory.FORM_UNKNOWN }
                "os" -> os = unquote(stripComment(rawValue))
                "cpuCores" -> cpu = unquote(stripComment(rawValue)).toIntOrNull() ?: return null
                "ramMB" -> ram = unquote(stripComment(rawValue)).toLongOrNull() ?: return null
                "storageFreeMB" -> storage = unquote(stripComment(rawValue)).toLongOrNull() ?: return null
                "screen" -> screen = unquote(stripComment(rawValue))
                "sensors" -> sensors = flowList(unquote(stripComment(rawValue)))
                "camera" -> camera = flowList(unquote(stripComment(rawValue)))
                "observedAt" -> observedAt = unquote(stripComment(rawValue))
                "schemaVersion" -> schemaVersion = unquote(stripComment(rawValue)).toIntOrNull() ?: return null
                "status" -> status = unquote(stripComment(rawValue)).ifBlank { DeviceInventory.STATUS_ACTIVE }
                "agents" -> {
                    val v = unquote(stripComment(rawValue))
                    if (v.isNotEmpty() && v != "[]") return null
                    inAgents = true
                    agentBuf = null
                }
                else -> extra[key] = unquote(stripComment(rawValue))
            }
        }
        if (!flushAgent()) return null
        if (deviceId.isBlank()) return null

        return DeviceInventory(
            deviceId = deviceId,
            legacyFingerprint = legacy,
            deviceName = name,
            formFactor = form,
            os = os,
            cpuCores = cpu,
            ramMB = ram,
            storageFreeMB = storage,
            screen = screen,
            sensors = sensors,
            camera = camera,
            observedAt = observedAt,
            schemaVersion = schemaVersion,
            status = status,
            agents = agents,
            extra = extra,
            body = body
        )
    }

    fun serialize(inv: DeviceInventory): String = buildString {
        append(DELIM).append('\n')
        append("deviceId: ").append(scalar(inv.deviceId)).append('\n')
        append("legacyFingerprint: ").append(scalar(inv.legacyFingerprint)).append('\n')
        append("deviceName: ").append(scalar(inv.deviceName)).append('\n')
        append("formFactor: ").append(scalar(inv.formFactor)).append('\n')
        append("os: ").append(scalar(inv.os)).append('\n')
        append("cpuCores: ").append(inv.cpuCores).append('\n')
        append("ramMB: ").append(inv.ramMB).append('\n')
        append("storageFreeMB: ").append(inv.storageFreeMB).append('\n')
        append("screen: ").append(scalar(inv.screen)).append('\n')
        append("sensors: ").append(flowListText(inv.sensors)).append('\n')
        append("camera: ").append(flowListText(inv.camera)).append('\n')
        append("observedAt: ").append(scalar(inv.observedAt)).append('\n')
        append("schemaVersion: ").append(inv.schemaVersion).append('\n')
        append("status: ").append(scalar(inv.status)).append('\n')
        if (inv.agents.isEmpty()) {
            append("agents: []").append('\n')
        } else {
            append("agents:").append('\n')
            inv.agents.forEach { agent ->
                append("  - name: ").append(scalar(agent.name)).append('\n')
                if (agent.model.isNotBlank()) append("    model: ").append(scalar(agent.model)).append('\n')
                if (agent.contextTokens > 0) append("    contextTokens: ").append(agent.contextTokens).append('\n')
                agent.vision?.let { append("    vision: ").append(it).append('\n') }
                agent.tools?.let { append("    tools: ").append(it).append('\n') }
                if (agent.plugins.isNotEmpty()) append("    plugins: ").append(flowListText(agent.plugins)).append('\n')
                if (agent.observedAt.isNotBlank()) append("    observedAt: ").append(scalar(agent.observedAt)).append('\n')
                if (agent.sources.isNotEmpty()) append("    sources: ").append(flowMapText(agent.sources)).append('\n')
                agent.extra.forEach { (k, v) -> append("    ").append(k).append(": ").append(scalar(v)).append('\n') }
            }
        }
        inv.extra.forEach { (k, v) ->
            if (k in DEVICE_KEYS || k == "agents") return@forEach
            append(k).append(": ").append(scalar(v)).append('\n')
        }
        append(DELIM).append('\n')
        val bodyText = inv.body.trim('\n')
        if (bodyText.isNotBlank()) append('\n').append(bodyText).append('\n')
    }.trimEnd('\n') + "\n"

    // ── 行内值助手 ─────────────────────────────────────────────────

    private fun boolOrNull(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** 去掉非引号值后的行内注释 (` # ...`); 引号内的 `#` 不切。 */
    private fun stripComment(raw: String): String {
        val v = raw.trim()
        if (v.startsWith("'") || v.startsWith("\"")) return v
        val idx = v.indexOf(" #")
        return if (idx >= 0) v.substring(0, idx).trim() else v
    }

    private fun unquote(raw: String): String {
        val v = raw.trim()
        if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length - 1).replace("''", "'")
        }
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) return v.substring(1, v.length - 1)
        return v
    }

    /** `[a, b]` → List; 标量形态按单值接受。 */
    private fun flowList(raw: String): List<String> {
        val v = raw.trim()
        if (v.isEmpty() || v == "[]") return emptyList()
        if (!v.startsWith("[") || !v.endsWith("]")) return listOf(v).filter { it.isNotBlank() }
        return v.substring(1, v.length - 1).split(',').map { unquote(it) }.filter { it.isNotBlank() }
    }

    /** `{model: measured, vision: declared}` → Map。 */
    private fun flowMap(raw: String): Map<String, String> {
        val v = raw.trim()
        if (v.isEmpty() || v == "{}") return emptyMap()
        if (!v.startsWith("{") || !v.endsWith("}")) return emptyMap()
        val out = LinkedHashMap<String, String>()
        v.substring(1, v.length - 1).split(',').forEach { pair ->
            val idx = pair.indexOf(':')
            if (idx > 0) out[pair.take(idx).trim()] = unquote(pair.drop(idx + 1))
        }
        return out
    }

    private fun flowListText(values: List<String>): String =
        if (values.isEmpty()) "[]" else values.joinToString(", ", "[", "]") { scalar(it) }

    private fun flowMapText(map: Map<String, String>): String =
        if (map.isEmpty()) "{}" else map.entries.joinToString(", ", "{", "}") { "${it.key}: ${scalar(it.value)}" }

    /** 标量编码: 空值 `""`; 含 YAML 特殊字符时单引号包裹 (内嵌单引号翻倍)。 */
    private fun scalar(value: String): String {
        val v = value.replace('\n', ' ').trim()
        if (v.isEmpty()) return "\"\""
        val needsQuote = v.any { it in "#[]{}\"'" } || v.contains(": ") || v.endsWith(":") ||
            v.startsWith("-") || v.startsWith("*") || v.startsWith("&") || v.startsWith("!")
        return if (needsQuote) "'" + v.replace("'", "''") + "'" else v
    }

    private val DEVICE_KEYS = setOf(
        "deviceId", "legacyFingerprint", "deviceName", "formFactor", "os", "cpuCores",
        "ramMB", "storageFreeMB", "screen", "sensors", "camera", "observedAt",
        "schemaVersion", "status"
    )
    private val AGENT_KEYS = setOf(
        "name", "model", "contextTokens", "vision", "tools", "plugins", "observedAt", "sources"
    )
}
