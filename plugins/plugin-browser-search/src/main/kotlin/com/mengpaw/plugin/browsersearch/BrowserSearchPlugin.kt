// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.browsersearch

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网页转档插件 (browser-search 保持 id 兼容)。
 *
 * 能力面:
 * 1. **网页转 Markdown 管道** — clean 提取正文 → md 转 Markdown 存
 *    `DataPaths.SEARCH_OUTPUTS`, 供 Agent 阅读/提炼, 回传浏览器预览
 *    (抓取用 net.curl, 本插件不做网络抓取 — 避免与内核重复造轮子)
 * 2. **搜索引擎结果提取** — 生成各搜索引擎结果页的提取 JS, 交 browser.eval 执行
 */
class BrowserSearchPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "browser-search-plugin",
        name = "网页转档",
        version = "0.20.2",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "网页转 Markdown 管道：clean 提取正文 → md 生成文档 → outputs/clear 输出管理；网页抓取复用 net.curl，高质量搜索用 tavily",
        permissions = emptyList(),
        minCoreVersion = "0.2.3",
        commands = listOf(
            "search.extract", "search.summary", "search.engines",
            "search.clean", "search.md", "search.outputs", "search.clear"
        )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "extract" to ::extract,
        "summary" to ::summary,
        "engines" to ::engines,
        "clean" to ::clean,
        "md" to ::md,
        "outputs" to ::outputs,
        "clear" to ::clear,
    )

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                // SECURITY: 关闭自动重定向 — 跟随前必须手动复查 Location 目标 (SSRF 防绕过:
                // 重定向到内网/回环地址可绕过私有 IP 黑名单)
                followRedirects(false)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
    }

    private val tsFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ── search.extract ──────────────────────────────────────────────────

    private suspend fun extract(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val engine = detectEngine(args.firstOrNull())
        val js = extractionScript(engine)
        return ExecutionResult.ok(
            "## 搜索结果提取 ($engine)\n\n" +
            "将以下 JS 注入浏览器以提取结果:\n\n" +
            "```js\n$js\n```\n\n" +
            "提示: 使用 browser.eval 执行上述代码。"
        )
    }

    // ── search.summary ──────────────────────────────────────────────────

    private suspend fun summary(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
## 网页转档使用指南

### 工作流
1. browser.open https://www.google.com/search?q=关键词
2. search.extract google  → 获取提取脚本
3. browser.eval <脚本>    → 执行提取
4. 分析返回的 JSON 结果

### 网页转 Markdown 管道
1. net.curl <url>                   → 抓取网页 (复用内核网络命令)
2. search.clean <url|路径> [--save] → 提取正文去噪
3. search.md <url|路径> [--name x]  → 转 Markdown 保存到输出目录 (内部抓取, 一步到位)
4. search.outputs                   → 查看已转换文档
5. search.clear [--all]             → 清理过期输出

### 支持的搜索引擎
| 引擎 | 搜索URL | 提取精确度 |
|------|--------|-----------|
| Google | google.com/search?q=... | 高 (标题+摘要+链接) |
| 百度 | baidu.com/s?wd=... | 高 (标题+摘要+链接) |
| Bing | bing.com/search?q=... | 高 (标题+摘要+链接) |
| DuckDuckGo | duckduckgo.com/?q=... | 中 (标题+摘要+链接) |
""".trimIndent())
    }

    private suspend fun engines(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
支持的搜索引擎: google, bing, baidu, duckduckgo
用法: search.extract [引擎名]
""".trimIndent())
    }

    // ── search.clean ────────────────────────────────────────────────────

    private suspend fun clean(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val source = args.firstOrNull()
            ?: return ExecutionResult.fail("用法: search.clean <url|本地html路径> [--save]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val save = args.contains("--save")

        val (html, srcErr) = loadSource(source)
        if (html == null) return ExecutionResult.fail(srcErr ?: "无法读取内容: $source")
        val main = HtmlConverter.extractMainContainer(html)
        val text = HtmlConverter.toMarkdown(HtmlConverter.stripNoiseTags(main), null).trim()

        val sb = StringBuilder()
        sb.append("## 正文提取\n")
        sb.append("- 来源: $source\n")
        sb.append("- 字符数: ${text.length}\n")
        if (save) {
            val cleanDir = File(SEARCH_OUTPUTS, "clean").apply { mkdirs() }
            val file = File(cleanDir, "${HtmlConverter.sanitizeFileName(File(source).nameWithoutExtension.ifBlank { "page" })}_${tsFmt.format(Date())}.txt")
            file.writeText(text)
            sb.append("- 已保存: ${file.absolutePath}\n")
        }
        sb.append("\n${text.take(8000)}")
        return ExecutionResult.ok(sb.toString())
    }

    // ── search.md ───────────────────────────────────────────────────────

    private suspend fun md(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val source = args.firstOrNull()
            ?: return ExecutionResult.fail("用法: search.md <url|本地html路径> [--name x]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args.argValue("--name")

        val (html, srcErr) = loadSource(source)
        if (html == null) return ExecutionResult.fail(srcErr ?: "无法读取内容: $source")
        val title = Regex("(?i)<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim()?.take(120)
            ?: (if (source.startsWith("http")) source else File(source).nameWithoutExtension)

        val markdown = HtmlConverter.convert(html, HtmlConverter.decodeEntities(title))

        val outDir = File(SEARCH_OUTPUTS).apply { mkdirs() }
        val baseName = HtmlConverter.sanitizeFileName(name ?: title)
        val file = File(outDir, "${baseName}_${tsFmt.format(Date())}.md")
        file.writeText(markdown)

        return ExecutionResult.ok(
            "## Markdown 已生成\n" +
            "- 来源: $source\n" +
            "- 文件: ${file.absolutePath}\n" +
            "- 字符数: ${markdown.length}\n" +
            "- 预览:\n\n```markdown\n${markdown.take(300)}\n```\n\n" +
            "> 用 `agent.read <路径>` 阅读全文, 或浏览器预览。"
        )
    }

    // ── search.outputs ──────────────────────────────────────────────────

    private suspend fun outputs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val all = args.contains("--all")
        val sortSize = args.contains("--sort size")
        val dir = File(SEARCH_OUTPUTS)
        if (!dir.exists()) return ExecutionResult.ok("输出目录为空: ${dir.absolutePath}")

        val files = dir.listFiles { f -> f.isFile }?.toList().orEmpty()
            .sortedWith(if (sortSize) compareByDescending<File> { it.length() } else compareByDescending { it.lastModified() })
        val shown = if (all) files else files.take(20)

        if (shown.isEmpty()) return ExecutionResult.ok("输出目录为空: ${dir.absolutePath}")

        val sb = StringBuilder("## 搜索输出 (${files.size} 个文件, 显示 ${shown.size})\n\n")
        shown.forEach { f ->
            val size = when {
                f.length() > 1024 * 1024 -> "%.1f MB".format(f.length() / 1024.0 / 1024.0)
                f.length() > 1024 -> "${f.length() / 1024} KB"
                else -> "${f.length()} B"
            }
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))
            sb.append("- [${if (f.extension == "md") "md" else f.extension}] ${f.name} ($size, $time)\n")
        }
        sb.append("\n> 用 `search.clear` 清理过期输出。")
        return ExecutionResult.ok(sb.toString())
    }

    // ── search.clear ────────────────────────────────────────────────────

    private suspend fun clear(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val all = args.contains("--all")
        val olderThanDays = args.argValue("--older-than")?.toIntOrNull() ?: 7
        val dir = File(SEARCH_OUTPUTS)
        if (!dir.exists()) return ExecutionResult.ok("输出目录不存在: ${dir.absolutePath}")

        val cutoff = System.currentTimeMillis() - olderThanDays * 24 * 3600 * 1000L
        val candidates = dir.walkTopDown().filter { it.isFile && it.parentFile.absolutePath.startsWith(dir.absolutePath) }
        val targets = if (all) {
            candidates.toList()
        } else {
            candidates.filter { it.extension == "md" && it.lastModified() < cutoff }.toList()
        }

        targets.forEach { it.delete() }
        // 清理空子目录
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            if (sub.listFiles()?.isEmpty() == true) sub.delete()
        }
        return ExecutionResult.ok("已清理 ${targets.size} 个文件 (保留目录 ${dir.absolutePath})")
    }

    // ── Shared helpers ──────────────────────────────────────────────────

    private val SEARCH_OUTPUTS get() = com.mengpaw.kernel.DataPaths.SEARCH_OUTPUTS

    /** 来源统一入口: URL → 网络抓取; 本地路径 → 读文件 (仅限 SEARCH_OUTPUTS 内)。返回 (内容, 错误)。 */
    private suspend fun loadSource(source: String): Pair<String?, String?> {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            val err = validateUrl(source)
            if (err != null) return null to err
            return try {
                withContext(Dispatchers.IO) {
                    // 手动跟随重定向: 每跳 Location 目标重新过 SSRF 校验, 最多 5 跳
                    var url = source
                    var redirects = 0
                    var body = ""
                    while (true) {
                        val resp = client.get(url) { header("User-Agent", "Mozilla/5.0 (Linux; Android 13; MengPaw-Browser/0.20)") }
                        if (resp.status.value in 300..399) {
                            redirects++
                            if (redirects > 5) throw IllegalStateException("重定向次数过多 (5 次上限)")
                            val loc = resp.headers["Location"] ?: throw IllegalStateException("重定向响应缺少 Location")
                            val next = try { java.net.URI(url).resolve(loc).toString() } catch (e: Exception) {
                                throw IllegalStateException("非法重定向目标: $loc") }
                            val redirErr = validateUrl(next)
                            if (redirErr != null) throw IllegalStateException("重定向目标被拒绝: $redirErr")
                            url = next
                            continue
                        }
                        body = resp.bodyAsText()
                        break
                    }
                    body
                } to null
            } catch (e: Exception) {
                ErrorCollector.report(e, "BrowserSearch.loadSource")
                null to "抓取失败: ${e.message ?: "网络错误"}"
            }
        }
        val f = File(source)
        if (!f.exists()) return null to "文件不存在: $source"
        if (!f.absolutePath.startsWith(File(SEARCH_OUTPUTS).absolutePath)) return null to "路径越界: 仅允许读取 SEARCH_OUTPUTS 内的文件"
        return try {
            f.readText() to null
        } catch (e: Exception) {
            ErrorCollector.report(e, "BrowserSearch.loadSource")
            null to "读取失败: ${e.message}"
        }
    }

    /** SSRF 防护 (与 plugin-net 同模式): 仅 http/https、拒绝内网/回环/云元数据。
     *  internal 为测试可见性 (SSRF 单测直连校验函数)。 */
    internal suspend fun validateUrl(rawUrl: String): String? {
        val uri = try {
            val u = URI(rawUrl)
            if (!u.isAbsolute) return "Only absolute URLs are allowed"
            u
        } catch (e: Exception) {
            return "Invalid URL: ${e.message}"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Blocked scheme '$scheme': only http/https are allowed"
        val host = uri.host ?: return "URL has no host"
        return try {
            val addr = withContext(Dispatchers.IO) { InetAddress.getByName(host) }
            if (isBlockedAddress(addr)) "Blocked internal address: $host (${addr.hostAddress})" else null
        } catch (e: Exception) {
            "Cannot resolve host: $host"
        }
    }

    /** internal 为测试可见性 (SSRF 单测直连黑名单矩阵)。 */
    internal fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress) return true
        val ip = addr.hostAddress ?: return false
        if (ip == "169.254.169.254") return true  // AWS / GCP metadata
        if (ip == "100.100.100.200") return true  // Alibaba Cloud metadata
        if (ip == "::ffff:127.0.0.1") return true
        return false
    }

    /** 解析 `--key value` 风格参数。 */
    private fun List<String>.argValue(key: String): String? {
        val i = indexOf(key)
        return if (i >= 0 && i + 1 < size) get(i + 1) else null
    }

    // ── Engine detection ────────────────────────────────────────────────

    private fun detectEngine(name: String?): String {
        return when (name?.lowercase()) {
            "google" -> "google"
            "bing" -> "bing"
            "baidu", "百度" -> "baidu"
            "duckduckgo", "ddg" -> "duckduckgo"
            else -> "google" // default
        }
    }

    // ── Extraction scripts per engine ───────────────────────────────────

    private fun extractionScript(engine: String): String = when (engine) {
        "google" -> """
(function(){
  var r=[];document.querySelectorAll('.g,[data-sokoban-container]').forEach(function(g){
    var h=g.querySelector('h3');var a=g.querySelector('a[href]');
    var s=g.querySelector('[data-sncf]')||g.querySelector('.VwiC3b')||g.querySelector('span');
    if(h&&a)r.push({title:h.textContent.trim(),url:a.href,snippet:(s?s.textContent.trim():'')});
  });
  return JSON.stringify({engine:'google',count:r.length,results:r});
})()""".trimIndent()

        "baidu" -> """
(function(){
  var r=[];document.querySelectorAll('.result,.c-container').forEach(function(c){
    var h=c.querySelector('h3 a');var s=c.querySelector('.c-abstract,.content-right_8Zs40');
    if(h)r.push({title:h.textContent.trim(),url:h.href,snippet:(s?s.textContent.trim():'')});
  });
  return JSON.stringify({engine:'baidu',count:r.length,results:r});
})()""".trimIndent()

        "bing" -> """
(function(){
  var r=[];document.querySelectorAll('.b_algo').forEach(function(b){
    var h=b.querySelector('h2 a');var s=b.querySelector('.b_caption p');
    if(h)r.push({title:h.textContent.trim(),url:h.href,snippet:(s?s.textContent.trim():'')});
  });
  return JSON.stringify({engine:'bing',count:r.length,results:r});
})()""".trimIndent()

        "duckduckgo" -> """
(function(){
  var r=[];document.querySelectorAll('.result').forEach(function(d){
    var h=d.querySelector('.result__a');var s=d.querySelector('.result__snippet');
    if(h)r.push({title:h.textContent.trim(),url:h.href,snippet:(s?s.textContent.trim():'')});
  });
  return JSON.stringify({engine:'duckduckgo',count:r.length,results:r});
})()""".trimIndent()

        else -> "console.log('Unknown engine: $engine')"
    }
}
