// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.MotionEvent
import android.net.http.SslError
import android.webkit.*
import android.webkit.WebResourceError
import android.widget.Toast
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.plugin.BrowserElement
import com.mengpaw.browser.plugin.BrowserPluginRegistry
import com.mengpaw.browser.ui.theme.BrowserThemeConfig
import com.mengpaw.browser.util.isAdRequest

// ── Block non-user-initiated popups ─────────────────────────────

// ── App-banner hiding CSS ───────────────────────────────────────

private val HIDE_APP_BANNER_JS = """
(function(){if(window.__mpHideBanner)return;window.__mpHideBanner=true;
var s=document.createElement('style');s.id='mp-hide-banner';
s.textContent=[
'#smartbanner,.smartbanner,[class*=app-banner],[class*=app_banner]',
'[class*=open-app],[class*=openApp],[class*=OpenInApp],[class*=open_in_app]',
'[class*=app-download],[class*=appDownload],[class*=mobile-app]',
'[class*=native-app],.app-install,.appInstallBanner',
'[id*=smartbanner],[id*=app-banner],[id*=app_banner]',
'.weibo-app-banner,.open-in-app,.openAppBtn',
'.downloadApp,.download-app,.download_app',
'.app-promotion,.appPromotion,.app-guide,.appGuide',
'[class*=float-app],[class*=app-float],[class*=floatBtn]',
'[class*=bottom-bar],[class*=bottomBar],[class*=openIn]',
'.OpenInAppButton,.Button--openInApp,.AppHeader',
'.MobileAppHeader__button,.sohu-app-bar,.appBtn',
'.open-app-btn,.open-in-app-btn,.go-app-btn',
'[class*=goToApp],[class*=launchApp],[class*=appLink]',
'[class*=app-guide-bottom],[class*=bottom-app]'
].join(',')+'{display:none!important}';
document.head.appendChild(s)})();
""".trimIndent()

// ── WebView Factory ──────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
fun createWebView(
    ctx: android.content.Context, tab: TabState, isWide: Boolean, adBlock: Boolean,
    autoInject: Boolean = true,
    updateTab: (Int, (TabState) -> TabState) -> Unit,
    onMediaDetected: (List<DetectedImage>) -> Unit,
    onScroll: (Int) -> Unit = {}
): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // Allow file:// access for local Markdown/HTML files
    settings.allowFileAccess = true
    // SECURITY: Disable third-party cookies to prevent cross-site tracking
    try { CookieManager.getInstance().setAcceptThirdPartyCookies(this, false) } catch (_: Exception) { }
    try { CookieManager.getInstance().setAcceptCookie(true) } catch (_: Exception) { }

    // Agent-to-browser bridge — enables Agent to control this WebView via JS
    addJavascriptInterface(
        com.mengpaw.browser.bridge.BrowserBridge(this) { bitmap ->
            var path = ""
            try {
                val dir = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS)
                dir.mkdirs()
                val file = java.io.File(dir, "browser_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                path = file.absolutePath
            } catch (_: Exception) { }
            path
        },
        "MengPaw"
    )

    var lastScrollYLocal = 0
    setOnScrollChangeListener { _, _, scrollY, _, _ ->
        if (!isWide) {
            val delta = scrollY - lastScrollYLocal
            if (delta > 10) onScroll(delta)  // scrolling down
            else if (delta < -5) onScroll(delta)  // scrolling up
            lastScrollYLocal = scrollY
        }
    }

    var touchX = 0f; var touchY = 0f
    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) { touchX = event.x; touchY = event.y }
        false
    }

    // ── Long press: detect images + videos at touch point ──
    setOnLongClickListener {
        val js = """
            (function(){
                var els=document.elementsFromPoint($touchX,$touchY);
                var r=[];
                for(var i=0;i<els.length;i++){
                    var el=els[i];
                    var src=el.src||el.getAttribute('src')||el.style.backgroundImage||el.getAttribute('poster')||'';
                    var tag=el.tagName||'';
                    var mt=tag==='VIDEO'?'video':'image';
                    if(tag==='IMG'||tag==='VIDEO'||tag==='SOURCE'||src){
                        src=src.replace(/url\(["']?/,'').replace(/["']?\)/,'');
                        if(src&&src!=='none'&&!src.startsWith('data:')){
                            r.push(JSON.stringify({
                                src:src,alt:el.alt||'',tag:tag,
                                width:el.naturalWidth||el.videoWidth||el.width||0,
                                height:el.naturalHeight||el.videoHeight||el.height||0,
                                z:i,mediaType:mt
                            }));
                        }
                    }
                }
                return '['+r.join(',')+']';
            })();
        """.trimIndent()
        evaluateJavascript(js) { json ->
            try {
                val arr = org.json.JSONArray(json)
                val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    DetectedImage(it.getString("src"), it.optString("alt"), it.optInt("width"), it.optInt("height"), it.optInt("z"), it.optString("mediaType", "image"))
                }
                if (list.isNotEmpty()) {
                    onMediaDetected(list)
                    // Also dispatch to plugins
                    list.firstOrNull()?.let { img ->
                        BrowserPluginRegistry.onLongPress(
                            BrowserElement(type = img.mediaType.uppercase(), url = img.src, alt = img.alt, width = img.width, height = img.height)
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        true
    }

    webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // SECURITY: Reject all SSL certificate errors — no bypass allowed
            handler?.cancel()
            android.util.Log.e("BrowserActivity", "SSL error: ${error?.primaryError} for ${error?.url}")
            // Show user-facing feedback
            val host = try { java.net.URI(error?.url ?: "").host } catch (_: Exception) { error?.url ?: "" }
            view?.post {
                Toast.makeText(ctx, "SSL 证书错误，已阻止加载: $host", Toast.LENGTH_LONG).show()
            }
        }
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            // Only replace content for main frame errors, not subresources (images/scripts)
            if (request?.isForMainFrame != true) return
            val failingUrl = request?.url?.toString() ?: view?.url ?: ""
            val desc = error?.description?.toString() ?: "未知错误"
            val html = "<html><body style='padding:40px;font-family:sans-serif;text-align:center'>" +
                "<h2>页面加载失败</h2><p>${desc}</p>" +
                "<p style='color:#888;font-size:14px'>${failingUrl.take(100)}</p></body></html>"
            view?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // SECURITY: Block dangerous URL schemes (javascript:, file:, content:, intent:, etc.)
            val url = request?.url?.toString() ?: return false
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                if (url.startsWith("file://")) return false  // allow local file access
                if (url.startsWith("intent://") || url.startsWith("tel:") ||
                    url.startsWith("sms:") || url.startsWith("mailto:")) {
                    // Allow system intent schemes (handled by Android Intent system)
                    try {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) { }
                    return true
                }
                // Block javascript:, content:, data:, and other dangerous schemes
                android.util.Log.w("BrowserActivity", "Blocked unsafe URL scheme: ${url.take(80)}")
                return true
            }
            return false
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            url?.let { u -> updateTab(tab.id) { it.copy(url = u, isLoading = true) }; BrowserPluginRegistry.onPageStarted(u) }
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            updateTab(tab.id) { it.copy(isLoading = false, title = view?.title ?: "", canGoBack = view?.canGoBack() ?: false, canGoForward = view?.canGoForward() ?: false) }
            url?.let { u ->
                view?.title?.let { t -> BrowserPluginRegistry.onPageFinished(u, t) }
                // Inject plugin scripts
                BrowserPluginRegistry.injectScripts(u)?.let { js -> evaluateJavascript(js, null) }
                BrowserPluginRegistry.injectStyles(u)?.let { css ->
                    evaluateJavascript("(function(){var s=document.createElement('style');s.textContent='$css';document.head.appendChild(s);})()", null)
                }
                // Hide app-banner elements
                evaluateJavascript(HIDE_APP_BANNER_JS, null)
                // Auto-inject __mp bridge for faster Agent commands (if enabled)
                if (autoInject) {
                    evaluateJavascript("(function(){if(!window.__mp||!window.__mp._v){" +
                        "window.__mp={_v:1,_cache:{}," +
                        "c:function(s){var e=document.querySelector(s);if(!e)return JSON.stringify({ok:false,error:'not found:'+s});e.click();return JSON.stringify({ok:true,tag:e.tagName})}," +
                        "t:function(s,v){var e=document.querySelector(s);if(!e)return JSON.stringify({ok:false});e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;d.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));return JSON.stringify({ok:true})}," +
                        "sc:function(x,y){window.scrollBy(x,y);return JSON.stringify({ok:true,sx:window.scrollX,sy:window.scrollY})}," +
                        "ct:function(){try{var ls=[];document.querySelectorAll('a[href]').forEach(function(a){var t=(a.textContent||'').trim().substring(0,80);if(t&&a.href&&!a.href.startsWith('javascript:'))ls.push({text:t,href:a.href})});return JSON.stringify({title:document.title,url:location.href,links:ls.slice(0,50),text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().substring(0,3000)})}catch(e){return JSON.stringify({error:e.message})}}," +
                        "df:function(){var cur=window.__mp._cache._content||'';var raw=document.body?document.body.innerText:'';var fresh=raw.replace(/\\s+/g,' ').trim().substring(0,1000);window.__mp._cache._content=fresh;if(cur===fresh)return JSON.stringify({changed:false});return JSON.stringify({changed:true,added:fresh.substring(cur.length>0?function(a,b){for(var i=0;i<Math.min(a.length,b.length)&&a[i]===b[i];i++);return i}(cur,fresh):0)})}" +
                        "};return JSON.stringify({ok:true,msg:'__mp injected (auto)'})" +
                        "}})()", null)
                }
                // ComfyUI theme following: inject MengPaw theme colors
                if (u.contains(":${com.mengpaw.kernel.ports.Ports.COMFYUI}") || u.contains("comfyui", ignoreCase = true) || u.contains("comfy", ignoreCase = true)) {
                    val theme = BrowserThemeConfig.load(ctx)
                    val primary = "#" + java.lang.Long.toHexString(theme.primary).takeLast(6).uppercase()
                    evaluateJavascript("""
(function(){
	var s=document.createElement('style');
	s.textContent=`
:root{--comfy-primary:${primary};--comfy-bg:${if (theme.surface == 0xFFFFFFFFL) "#FFFFFF" else "#1A1A2E"}}
.comfy-menu, .comfy-topbar, .comfy-btn-primary{background:var(--comfy-primary)!important}
.comfy-multiline-input, .comfy-modal-content{background:var(--comfy-bg)!important}
.comfy-node{background:${primary}11!important;border-color:${primary}44!important}
.comfy-btn-primary:hover{filter:brightness(1.1)}
`;
	document.head.appendChild(s);
})();
""".trimIndent(), null)
                }
            }
        }
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            request?.let { BrowserPluginRegistry.shouldIntercept(it)?.let { return it } }
            if (adBlock && request?.url != null && isAdRequest(request.url.toString())) {
                return WebResourceResponse("text/plain", "utf-8", null)
            }
            return super.shouldInterceptRequest(view, request)
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, p: Int) { updateTab(tab.id) { it.copy(progress = p) } }
        override fun onReceivedTitle(view: WebView?, t: String?) { updateTab(tab.id) { it.copy(title = t ?: "") } }
    }

    if (tab.url.isNotBlank()) loadUrl(tab.url)
}
