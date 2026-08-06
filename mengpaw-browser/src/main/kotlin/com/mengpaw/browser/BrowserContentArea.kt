// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.ui.NewTabPage
import com.mengpaw.browser.web.createWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览器主内容区（自 BrowserApp 拆出 — 400 行文件拆分批次 2）。
 * 冷启动 NewTabPage / 常态 WebView 多标签渲染 (pre-render + visibility-toggle)。
 *
 * @param onScroll 滚动偏移回调 (工具栏动画)
 * @param onShowMarkdown 站内 .md URL 拉取成功后的展示回调 (BrowserApp 注入 mdContent/showMdViewer)
 */
@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
internal fun ColumnScope.BrowserContentArea(
    isColdStart: Boolean,
    tabs: List<TabState>,
    activeTab: TabState,
    activeTabId: Int,
    webViewMap: MutableMap<Int, WebView>,
    isWide: Boolean,
    adBlockEnabled: Boolean,
    autoInjectBridge: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchEngine: SearchEngine,
    onSearchEngineCycle: () -> Unit,
    prefs: BrowserPrefs,
    updateTab: (Int, (TabState) -> TabState) -> Unit,
    onImagesDetected: (List<DetectedImage>) -> Unit,
    onScroll: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onShowBookmarks: () -> Unit,
    onShowMarkdown: (String) -> Unit
) {
    if (isColdStart) {
        NewTabPage(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            searchEngine = searchEngine,
            onSearchEngineCycle = onSearchEngineCycle,
            isWide = isWide,
            prefs = prefs,
            onNavigate = onNavigate,
            onShowBookmarks = onShowBookmarks
        )
    } else {
        // WebView with pull-to-refresh
        val pullState = rememberPullRefreshState(
            refreshing = activeTab.isLoading,
            onRefresh = { webViewMap[activeTabId]?.reload() }
        )
        // Pre-render: keep all WebViews alive, visibility-toggle instead of destroy
        Box(Modifier.weight(1f).pullRefresh(pullState)) {
            // 站内 .md URL → 拉取 → 预览 (与 OPEN_MD 通道共用 mdContent/showMdViewer 状态)
            val mdFetchScope = rememberCoroutineScope()
            val appCtx = LocalContext.current
            fun fetchMarkdownUrl(url: String) {
                mdFetchScope.launch(Dispatchers.IO) {
                    val text = runCatching { fetchUrlTextTop(url) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (text != null) {
                            onShowMarkdown(text)
                        } else {
                            Toast.makeText(appCtx, "无法加载 .md 文档", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            tabs.forEach { tab ->
                val isActive = tab.id == activeTabId
                androidx.compose.runtime.key(tab.id) {
                    AndroidView(
                        factory = { ctx ->
                            val wv = webViewMap[tab.id]
                            if (wv != null) wv
                            else createWebView(
                                ctx, tab, isWide, adBlockEnabled, autoInjectBridge, updateTab,
                                onImagesDetected,
                                onScroll = onScroll,
                                onMarkdownDetected = { url -> fetchMarkdownUrl(url) }
                            )
                        },
                        update = { wv -> webViewMap[tab.id] = wv },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isActive) Modifier else Modifier.alpha(0f).height(0.dp))
                    )
                }
            }
            PullRefreshIndicator(activeTab.isLoading, pullState, Modifier.align(Alignment.TopCenter))
        }
    }
}
