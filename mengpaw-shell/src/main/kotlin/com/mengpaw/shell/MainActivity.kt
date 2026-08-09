// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.mengpaw.shell.ui.screens.AppRoot
import com.mengpaw.shell.ui.screens.AgentViewModel
import com.mengpaw.shell.ui.screens.SettingsViewModel
import com.mengpaw.shell.ui.screens.startAcpForTwin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 Activity — 生命周期 + URL 处理 + 延迟初始化。
 * 装配拆分: 关键路径初始化 → AppInitializer; Compose 根 → AppRoot (ui/screens)。
 */
class MainActivity : ComponentActivity() {
    private val settingsViewModel by viewModels<SettingsViewModel>()
    // 与 Compose 内 viewModel() 同实例 (同一 ViewModelStore), 用于浏览器提炼任务触发
    private val agentViewModel by viewModels<AgentViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── 关键路径初始化 (崩溃日志 / DataPaths / 插件管理器 / SysExecutor / 模板 / 日志器) ──
        AppInitializer.initialize(this)
        // ── 旧版无主进化档案迁移 (v0.34.x): Agent文档/default/ 被误建为 Agent 工作区 →
        //    进化档案/ 顶层。必须在任何 Agent 发现/列表扫描之前执行 (幂等, 无旧数据零开销)。 ──
        try { com.mengpaw.kernel.evolution.EvolutionStore.migrateLegacyDefaultDir() } catch (_: Exception) {}
        com.mengpaw.core.namespace.SysExecutor.setActivity(this)
        enableEdgeToEdge()

        // 启动阶段：深蓝背景 → 白色状态栏图标
        val window = window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        setContent { AppRoot(settingsViewModel) }
        // 延迟初始化: 非关键路径在 UI 渲染后异步执行
        val launchIntent = intent
        lifecycleScope.launch(Dispatchers.IO) { deferInit(launchIntent) }
    }

    /** Handle incoming OPEN_URL intent from Browser APK without creating a new task. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenUrl(intent)
    }

    /** Keep the Activity reference fresh for runtime permission dialogs. */
    override fun onResume() {
        super.onResume()
        com.mengpaw.core.namespace.SysExecutor.setActivity(this)
        // v0.35.1: 从『所有文件访问』授权页返回后重新探测输出目录 —
        // 启动时未授权回退私有 Android/data/..., 授权后实时切到公共 /MengPaw/
        try {
            com.mengpaw.core.DataPathsInitializer.refreshOutput(this)
        } catch (_: Exception) {}
    }

    /** Clear Activity reference to prevent leaks. */
    override fun onDestroy() {
        super.onDestroy()
        com.mengpaw.core.namespace.SysExecutor.setActivity(null)
    }

    private fun handleOpenUrl(intent: Intent?) {
        if (intent?.action == "com.mengpaw.action.OPEN_URL") {
            val url = intent.getStringExtra("url")
            if (url != null) {
                // mode=extract: 浏览器「提炼网页要点」→ 任务脚本 + 直接触发 Agent
                if (intent.getStringExtra("mode") == "extract") {
                    handleBrowserExtract(url, intent.getStringExtra("title") ?: url)
                    return
                }
                try {
                    val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
                    inbox.mkdirs()
                    val tmp = java.io.File(inbox, "browser_url_${System.currentTimeMillis()}.txt.tmp")
                    tmp.writeText(url)
                    val dest = java.io.File(inbox, "browser_url_${System.currentTimeMillis()}.txt")
                    tmp.renameTo(dest)
                    if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 浏览器网页提炼请求 — 写任务脚本到 inbox 并直接触发 Agent。
     * 触发失败时任务文件留档, 由 Agent 轮询 inbox 兜底; 成功后删除防重复执行。
     */
    private fun handleBrowserExtract(url: String, title: String) {
        val taskId = System.currentTimeMillis().toString()
        val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
        val taskFile = java.io.File(inbox, "browser_extract_$taskId.md")
        try {
            inbox.mkdirs()
            taskFile.writeText(buildString {
                appendLine("# 浏览器网页提炼任务")
                appendLine("- 类型: browser-extract")
                appendLine("- URL: $url")
                appendLine("- 标题: $title")
                appendLine("- 请求时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                appendLine("- 回传文件: browser_return_$taskId.md")
                appendLine()
                appendLine("## 任务")
                appendLine("抓取该网页转成 Markdown, 用 LLM 提炼核心要点, 写回传文件供浏览器预览。")
                appendLine()
                appendLine("## 执行步骤")
                appendLine("1. 若 search 命令不可用, 先执行: plugin.install browser-search-plugin")
                appendLine("2. search.md $url --name article_$taskId — 抓取(内部) + 转 Markdown 保存, 记住返回的文件路径")
                appendLine("3. agent.read <第2步返回的md路径>      — 阅读全文")
                appendLine("4. 提炼要点: 一句话总结 + 3~6 条核心要点 + 1 条关键数据或引用")
                appendLine("5. 写回传文件: agent.write ${inbox.absolutePath}/browser_return_$taskId.md")
                appendLine("   格式: # 标题 / [原文链接](URL) / ## 核心要点 (列表) / ## 总结 (一段话)")
                appendLine("6. agent.rm ${taskFile.absolutePath}   — 删除本任务文件, 防止重复执行")
                appendLine("7. Final Answer 告知用户: 提炼完成, 浏览器将自动弹出预览")
            })
            // 直接触发; 任务文件保留到 Agent 执行完成 (脚本步骤 8 由 agent.rm 删除, 防重复执行)
            agentViewModel.submitBrowserExtract(url, taskId)
        } catch (e: Exception) {
            android.util.Log.w("MengPaw", "handleBrowserExtract failed: ${e.message}", e)
        }
    }

    /**
     * 延迟初始化 — 非关键路径, 在 UI 渲染后执行.
     * 包含: 框架发现, 触发器引擎, 前台服务, 事件接收器, 插件注册/安装, URL 处理.
     */
    private suspend fun deferInit(launchIntent: Intent) = withContext(Dispatchers.Default) {
        // ── 孪生恢复 ──
        try { autoRestoreTwinIfNeeded() } catch (_: Exception) {}

        // ── Token 统计 ──
        try { com.mengpaw.shell.ui.components.TokenStatsCollector.load() } catch (_: Exception) {}

        // ── PluginViewModel 类注册 (装配清单见 PluginRegistrar) ──
        PluginRegistrar.registerPluginClasses()

        // ── 插件 Context 注入 (error-report / update 是 remote 插件, 反射设置静态字段, 未安装时静默跳过) ──
        try {
            Class.forName("com.mengpaw.plugin.errorreport.ErrorReportPlugin")
                .getField("appContext").set(null, this@MainActivity)
        } catch (_: Exception) {}
        try {
            Class.forName("com.mengpaw.plugin.update.UpdatePlugin")
                .getField("appContext").set(null, this@MainActivity)
        } catch (_: Exception) {}

        // ── 网络状况门卫 (v0.29.2): ConnectivityManager 回调 → 内核重试策略
        //    (断网快返 + 弱网放慢退避; 免危险权限, 仅 ACCESS_NETWORK_STATE) ──
        try { com.mengpaw.shell.service.NetworkConditionMonitor.attach(this@MainActivity) } catch (_: Exception) {}

        // ── 框架发现 (mDNS) ──
        try {
            com.mengpaw.plugin.framework.FrameworkIdentity.load(this@MainActivity)  // v0.34.3 名片 (名称/指纹)
            com.mengpaw.plugin.framework.FrameworkDiscovery.instance =
                com.mengpaw.plugin.framework.FrameworkDiscovery(this@MainActivity).apply {
                    frameworkName = "MengPaw"
                    frameworkVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION
                    val agentsDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS)
                    // 与本地列表同源过滤系统目录 — 本地所见 = 对外宣称（inbox/team/default 不是智能体）
                    // 统一判定 DataPaths.isAgentWorkspaceDir (v0.34.x: default 曾漏排除被误判为 Agent)
                    agentNames = agentsDir.listFiles()
                        ?.filter { it.isDirectory && com.mengpaw.kernel.DataPaths.isAgentWorkspaceDir(it.name) }
                        ?.map { it.name } ?: listOf("MengPaw")
                }
            com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.register()
            com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.startContinuousDiscovery()
        } catch (_: Exception) {}

        // ── 触发器引擎 ──
        try {
            com.mengpaw.kernel.trigger.TriggerEngine.setContext(this@MainActivity)
            com.mengpaw.kernel.trigger.TriggerEngine.load()
            com.mengpaw.kernel.trigger.TriggerEngine.registerSystemWake(this@MainActivity, 10)
            com.mengpaw.kernel.trigger.TriggerEngine.refreshCronAlarm()
        } catch (_: Exception) {}

        // ── 前台服务 ──
        try { com.mengpaw.shell.service.ShellService.start(this@MainActivity) } catch (_: Exception) {}

        // ── 浏览器回传监视 (幂等, 与 ShellService 双保险) ──
        try { com.mengpaw.shell.service.BrowserReturnWatcher.start(this@MainActivity) } catch (_: Exception) {}

        // ── 系统事件接收器 ──
        try { com.mengpaw.shell.service.EventReceiver.register(this@MainActivity) } catch (_: Exception) {}

        // ── 捆绑插件自动安装 (装配清单见 PluginRegistrar) ──
        PluginRegistrar.autoInstallBundled()

        // ── 处理外部 URL ──
        try { handleOpenUrl(launchIntent) } catch (_: Exception) {}
    }

    /** 如果之前激活过孪生, 自动恢复 ACP + 同步 (无需5连击) */
    private fun autoRestoreTwinIfNeeded() {
        val marker = java.io.File(filesDir, "twin_activated")
        if (!marker.exists()) return
        val agentName = try { marker.readText().trim() } catch (_: Exception) { "MengPaw" }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val plugin = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin()
                com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext = this@MainActivity
                com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.agentName = agentName
                val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
                pm.install(plugin)
                pm.activate(plugin.metadata.id)
                startAcpForTwin(this@MainActivity, agentName)
                android.util.Log.i("MengPawTwin", "孪生服务已自动恢复")
            } catch (e: Exception) {
                android.util.Log.w("MengPawTwin", "自动恢复失败: ${e.message}")
            }
        }
    }
}
