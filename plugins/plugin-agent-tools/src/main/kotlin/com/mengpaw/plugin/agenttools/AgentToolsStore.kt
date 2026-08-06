// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.agenttools

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单条外部 CLI 命令的定义（如 "gh pr list"）。
 */
data class AgentToolCommand(
    val name: String,
    val description: String = "",
    val usage: String = ""
)

/**
 * 一个外部 CLI 命令集（如 GitHub CLI、飞书 CLI）。
 * 对应 `Agent文档/{agent}/tools/{name}.json` 文件。
 */
data class AgentToolSet(
    val name: String,
    val displayName: String = "",
    val source: String = "",
    val importedAt: String = "",
    val commands: List<AgentToolCommand> = emptyList()
)

/**
 * Agent 命令集存储 — 校验 / 持久化 / 摘要生成。
 * 纯 JVM 可测（依赖 kernel 的 DataPaths，无 Android API 依赖）。
 */
object AgentToolsStore {

    const val MAX_COMMANDS_PER_SET = 200
    const val MAX_SETS_PER_AGENT = 20
    const val MAX_RAW_BYTES = 512 * 1024
    private const val MAX_COMMAND_NAME = 64
    private const val MAX_FIELD_LEN = 200

    private val nameRegex = Regex("^[a-zA-Z0-9_-]{1,32}$")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ── 路径 ──────────────────────────────────────────────────────────

    fun toolsDir(agentName: String): File = File(DataPaths.agentToolsDir(agentName))

    // ── 校验 + 解析 ───────────────────────────────────────────────────

    /**
     * 校验并解析命令集 JSON。CLI 参数 [cliName] 为权威名（覆盖清单内 name 字段）。
     * 返回失败消息或规范化后的 [AgentToolSet]。
     */
    fun parseAndValidate(cliName: String, rawJson: String): Result<AgentToolSet> {
        if (!nameRegex.matches(cliName)) {
            return Result.failure(IllegalArgumentException(
                "非法名称 '$cliName'：仅允许字母/数字/_/-，长度 1-32（防路径穿越）"))
        }
        if (rawJson.length > MAX_RAW_BYTES) {
            return Result.failure(IllegalArgumentException("命令集 JSON 超过 ${MAX_RAW_BYTES / 1024}KB 上限"))
        }
        return try {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("commands") ?: return Result.failure(
                IllegalArgumentException("缺少 commands 数组"))
            if (arr.length() < 1 || arr.length() > MAX_COMMANDS_PER_SET) {
                return Result.failure(IllegalArgumentException(
                    "commands 数量必须在 1~$MAX_COMMANDS_PER_SET 之间（当前 ${arr.length()}）"))
            }
            val commands = buildList {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val cmdName = c.optString("name", "").trim()
                    if (cmdName.isEmpty() || cmdName.length > MAX_COMMAND_NAME) {
                        return Result.failure(IllegalArgumentException(
                            "第 ${i + 1} 条命令 name 非法：非空且 ≤$MAX_COMMAND_NAME 字符"))
                    }
                    val desc = c.optString("description", "").trim()
                    val usage = c.optString("usage", "").trim()
                    if (desc.length > MAX_FIELD_LEN || usage.length > MAX_FIELD_LEN) {
                        return Result.failure(IllegalArgumentException(
                            "命令 '$cmdName' 的 description/usage 各 ≤$MAX_FIELD_LEN 字符"))
                    }
                    add(AgentToolCommand(cmdName, desc, usage))
                }
            }
            if (commands.isEmpty()) {
                return Result.failure(IllegalArgumentException("commands 数组为空"))
            }
            Result.success(AgentToolSet(
                name = cliName,
                displayName = root.optString("displayName", "").trim().take(64),
                source = root.optString("source", "").trim().take(256),
                importedAt = dateFormat.format(Date()),
                commands = commands
            ))
        } catch (e: org.json.JSONException) {
            Result.failure(IllegalArgumentException("JSON 解析失败: ${e.message}"))
        }
    }

    /**
     * SSRF 防护 (与 plugin-net 同模式): 仅 http/https、拒绝内网/回环/云元数据。
     * 返回 null 表示通过, 否则返回拒绝原因。
     */
    private fun validateUrl(rawUrl: String): String? {
        val uri = try {
            val u = java.net.URI(rawUrl)
            if (!u.isAbsolute) return "Only absolute URLs are allowed"
            u
        } catch (e: Exception) {
            return "Invalid URL: ${e.message}"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Blocked scheme '$scheme': only http/https are allowed"
        val host = uri.host ?: return "URL has no host"
        return try {
            val addr = InetAddress.getByName(host)
            if (isBlockedAddress(addr)) "Blocked internal address: $host (${addr.hostAddress})" else null
        } catch (e: Exception) {
            "Cannot resolve host: $host"
        }
    }

    private fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress) return true
        val ip = addr.hostAddress ?: return false
        if (ip == "169.254.169.254") return true  // AWS / GCP metadata
        if (ip == "100.100.100.200") return true  // Alibaba Cloud metadata
        if (ip == "::ffff:127.0.0.1") return true
        return false
    }

    // ── URL 拉取 ─────────────────────────────────────────────────────

    /**
     * 拉取远程命令集 JSON（仅 http/https，30s 超时，512KB 上限）。
     * 阻塞调用 — 需在 IO 线程执行。
     */
    fun fetch(url: String): Result<String> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("仅支持 http/https URL"))
        }
        val ssrfError = validateUrl(url)
        if (ssrfError != null) return Result.failure(IllegalArgumentException(ssrfError))
        // 手动跟随重定向: 关闭自动重定向 (instanceFollowRedirects=false), 每跳 Location
        // 目标重新过 SSRF 校验 — 防自动重定向到内网地址绕过私有 IP 黑名单, 最多 5 跳
        var currentUrl = url
        var redirects = 0
        try {
            while (true) {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "MengPaw/AgentTools")
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        redirects++
                        if (redirects > 5) {
                            return Result.failure(IllegalStateException("重定向次数过多 (5 次上限)"))
                        }
                        val location = conn.getHeaderField("Location")
                            ?: return Result.failure(IllegalStateException("HTTP $code 缺少 Location"))
                        val next = try {
                            java.net.URI(currentUrl).resolve(location).toString()
                        } catch (e: Exception) {
                            return Result.failure(IllegalStateException("非法重定向目标: $location"))
                        }
                        val redirErr = validateUrl(next)
                        if (redirErr != null) {
                            return Result.failure(IllegalStateException("重定向目标被拒绝: $redirErr"))
                        }
                        currentUrl = next
                        continue
                    }
                    if (code !in 200..299) {
                        return Result.failure(IllegalStateException("HTTP $code"))
                    }
                    val bytes = conn.inputStream.use { ins ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_RAW_BYTES) {
                                return Result.failure(IllegalStateException("响应超过 ${MAX_RAW_BYTES / 1024}KB 上限"))
                            }
                            buffer.write(buf, 0, n)
                        }
                        buffer.toByteArray()
                    }
                    val text = String(bytes, Charsets.UTF_8)
                    if (text.isBlank()) return Result.failure(IllegalStateException("响应为空"))
                    return Result.success(text)
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("拉取失败: ${e.message}"))
        }
    }

    // ── 持久化 ───────────────────────────────────────────────────────

    /**
     * 原子写（tmp + rename）。返回是否覆盖了已有命令集。
     */
    fun save(agentName: String, set: AgentToolSet): Result<Boolean> {
        return try {
            val dir = toolsDir(agentName).also { it.mkdirs() }
            val target = File(dir, "${set.name}.json")
            val overwritten = target.exists()
            val tmp = File(dir, "${set.name}.json.tmp")
            tmp.writeText(toJson(set))
            // 先删目标再 rename（Windows/Android 兼容，仿 AgentExecutor 原子写模式）
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return Result.failure(IllegalStateException("写入失败（rename）"))
            }
            Result.success(overwritten)
        } catch (e: Exception) {
            ErrorCollector.report(e, "AgentToolsStore.save")
            Result.failure(IllegalStateException("保存失败: ${e.message}"))
        }
    }

    /** 列出该 Agent 全部命令集，按名称排序；单个文件解析失败跳过。 */
    fun readAll(agentName: String): List<AgentToolSet> {
        val dir = toolsDir(agentName)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "json" && !f.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            ?.mapNotNull { readFile(it) }
            ?: emptyList()
    }

    /** 读取单个命令集文件，解析失败返回 null。 */
    fun readFile(file: File): AgentToolSet? {
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("commands") ?: JSONArray()
            val commands = buildList {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    add(AgentToolCommand(
                        name = c.optString("name", ""),
                        description = c.optString("description", ""),
                        usage = c.optString("usage", "")
                    ))
                }
            }
            AgentToolSet(
                name = root.optString("name", file.nameWithoutExtension),
                displayName = root.optString("displayName", ""),
                source = root.optString("source", ""),
                importedAt = root.optString("importedAt", ""),
                commands = commands
            )
        } catch (_: Exception) { null }
    }

    /** 删除命令集文件。 */
    fun remove(agentName: String, name: String): Boolean {
        return try {
            File(toolsDir(agentName), "$name.json").delete()
        } catch (_: Exception) { false }
    }

    // ── 展示 ─────────────────────────────────────────────────────────

    /** 序列化为文件 JSON（org.json 自动处理转义）。 */
    fun toJson(set: AgentToolSet): String {
        val root = JSONObject()
        root.put("name", set.name)
        root.put("displayName", set.displayName)
        root.put("source", set.source)
        root.put("importedAt", set.importedAt)
        val arr = JSONArray()
        set.commands.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name); put("description", c.description); put("usage", c.usage)
            })
        }
        root.put("commands", arr)
        return root.toString(2)
    }

    /** UI 展开视图（Markdown 表格）。 */
    fun toMarkdown(set: AgentToolSet): String = buildString {
        appendLine("## ${set.displayName.ifBlank { set.name }} (${set.name})")
        if (set.source.isNotBlank() || set.importedAt.isNotBlank()) {
            appendLine("来源: ${set.source.ifBlank { "手动粘贴" }} · 导入: ${set.importedAt}")
        }
        appendLine()
        appendLine("| 命令 | 说明 | 用法 |")
        appendLine("|---|---|---|")
        set.commands.forEach { c ->
            appendLine("| `${c.name}` | ${c.description} | `${c.usage}` |")
        }
    }

    // ── 摘要（系统提示词注入） ───────────────────────────────────────

    /**
     * 紧凑摘要：每命令集一节（名称 + 命令名列表），注入系统提示词。
     * 预算内截断 — 防止提示词膨胀（LESSONS 170）。
     */
    fun buildSummary(agentName: String, perSetBudget: Int = 400, totalBudget: Int = 2000): String {
        val sets = readAll(agentName)
        if (sets.isEmpty()) return ""
        val out = StringBuilder()
        sets.forEach { set ->
            val header = "### ${set.displayName.ifBlank { set.name }} (${set.name}) — ${set.commands.size} 条命令"
            val names = set.commands.joinToString(", ") { it.name }
            var block = "$header\n$names"
            if (block.length > perSetBudget) block = block.take(perSetBudget) + "…"
            out.append(block).append('\n')
        }
        if (out.length > totalBudget) {
            out.setLength(totalBudget)
            out.append("\n… (命令未列全, 用 tools.search <关键词> 精确检索)")
        }
        return out.toString().trim()
    }
}
