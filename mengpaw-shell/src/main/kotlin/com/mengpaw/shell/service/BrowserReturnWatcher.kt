// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.service

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mengpaw.kernel.DataPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 浏览器回传监视器 — 轮询 `inbox/browser_return_*.md`, 发现后经 OPEN_MD Intent
 * 发送给浏览器 APK 弹 Markdown 预览。
 *
 * 任务闭环: 浏览器「提炼网页要点」→ Shell 触发 Agent → Agent 写 browser_return_*.md
 * → 本监视器回传 → 浏览器 MarkdownViewerDialog 预览 → 删除文件。
 *
 * 大内容 (>400KB, Binder 1MB 事务缓冲安全阈值) 走 FileProvider URI 免 Binder 限制。
 * start 幂等, MainActivity.deferInit 与 ShellService.onCreate 双保险覆盖全部时序。
 */
object BrowserReturnWatcher {

    private const val POLL_INTERVAL_MS = 1500L
    private const val MAX_BINDER_MD = 400 * 1024
    private const val OPEN_MD_ACTION = "com.mengpaw.action.OPEN_MD"
    private const val BROWSER_PKG = "com.mengpaw.browser"

    @Volatile private var job: Job? = null
    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try { scan() } catch (_: Exception) {}
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** 扫描 inbox 回传文件, 逐个发送 OPEN_MD; 成功后删除文件。 */
    private suspend fun scan() {
        val ctx = appContext ?: return
        val inbox = File(DataPaths.AGENT_INBOX)
        if (!inbox.exists()) return

        val files = inbox.listFiles { f ->
            f.isFile && f.name.startsWith("browser_return_") && f.name.endsWith(".md")
        }?.sortedBy { it.lastModified() } ?: return
        if (files.isEmpty()) return

        for (file in files) {
            try {
                val content = file.readText()
                val parsed = parseReturn(content, file)
                if (parsed == null) { file.delete(); continue } // 无法解析, 丢弃防堆积
                sendOpenMd(ctx, parsed, file)
                if (file.exists()) file.delete() // 发送成功 (含未安装) 即清理
            } catch (_: Exception) { /* 保留文件, 下轮重试 */ }
        }
    }

    private data class ReturnDoc(val title: String, val url: String, val md: String)

    /** 解析 Agent 回传文件: 首行 # 标题, [原文链接](url) 或 - URL: 行取地址, 其余为 md 正文。 */
    private fun parseReturn(content: String, file: File): ReturnDoc? {
        val lines = content.lines()
        val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: file.nameWithoutExtension
        val url = Regex("""\[原文链接\]\((https?://[^)\s]+)\)""").find(content)?.groupValues?.get(1)
            ?: Regex("""^- URL: (\S+)$""", setOf(RegexOption.MULTILINE)).find(content)?.groupValues?.get(1)
            ?: ""
        val md = content.trim()
        return if (md.isBlank()) null else ReturnDoc(title, url, md)
    }

    private fun sendOpenMd(ctx: Context, doc: ReturnDoc, file: File) {
        val intent = Intent(OPEN_MD_ACTION).apply {
            setClassName(BROWSER_PKG, "$BROWSER_PKG.BrowserActivity")
            putExtra("title", doc.title)
            putExtra("url", doc.url)
            if (doc.md.length > MAX_BINDER_MD) {
                // Binder 1MB 限制: 超大 md 走 FileProvider (file_paths.xml 已映射 Agent文档/)
                try {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    putExtra("mdUri", uri.toString())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { putExtra("md", doc.md.take(MAX_BINDER_MD)) }
            } else {
                putExtra("md", doc.md)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // 浏览器未安装 → 无法送达, 丢弃文件防堆积 (用户装浏览器后新任务自然可用)
        if (intent.resolveActivity(ctx.packageManager) == null) {
            android.util.Log.w("MengPaw", "BrowserReturnWatcher: 浏览器未安装, 丢弃回传 ${file.name}")
            return
        }
        try { ctx.startActivity(intent) } catch (e: Exception) {
            android.util.Log.w("MengPaw", "BrowserReturnWatcher send failed: ${e.message}", e)
            throw e // 保留文件重试
        }
    }
}
