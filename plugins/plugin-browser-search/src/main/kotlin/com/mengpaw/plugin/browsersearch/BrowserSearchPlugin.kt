// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.browsersearch

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * Browser search analysis plugin.
 *
 * Injects JS into search engine result pages to extract structured data.
 * Supports Google, Bing, Baidu, and DuckDuckGo.
 *
 * Agent workflow: browser.open <search-url> → search.extract → analyze results
 */
class BrowserSearchPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "browser-search-plugin",
        name = "搜索分析",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "搜索引擎结果提取：Google/Bing/百度/DuckDuckGo 结构化数据",
        permissions = emptyList(),
        minCoreVersion = "0.2.3",
        commands = listOf("search.extract", "search.summary", "search.engines")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "extract" to ::extract,
        "summary" to ::summary,
        "engines" to ::engines,
    )

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
## 搜索分析使用指南

### 工作流
1. browser.open https://www.google.com/search?q=关键词
2. search.extract google  → 获取提取脚本
3. browser.eval <脚本>    → 执行提取
4. 分析返回的 JSON 结果

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
