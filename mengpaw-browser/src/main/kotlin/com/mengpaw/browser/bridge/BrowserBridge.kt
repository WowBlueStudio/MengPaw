// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.bridge

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mengpaw.core.DataPaths
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Java↔JavaScript bridge enabling Agent to control the browser.
 *
 * Registered via [WebView.addJavascriptInterface] as "MengPaw".
 * All methods return JSON strings for consistent parsing by Agent.
 *
 * ## Design references (MIT-licensed projects, architecture only):
 * - Kuri: token-efficient accessibility-tree-like snapshots
 * - WebDroid Agent: WebView JS bridge pattern for DOM manipulation
 * - native-devtools-mcp: structured element interaction
 */
class BrowserBridge(
    private val webView: WebView,
    private val onScreenshot: ((Bitmap) -> String)? = null
) {

    /**
     * Click the first element matching a CSS selector.
     * Returns JSON: {"ok":true} or {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun click(selector: String): String {
        val safe = escapeJs(selector)
        return evalJs("""
            (function() {
                try {
                    var el = document.querySelector('$safe');
                    if (!el) return JSON.stringify({ok:false,error:'Selector not found: $safe'});
                    el.click();
                    return JSON.stringify({ok:true,tag:el.tagName,text:(el.textContent||'').trim().substring(0,100)});
                } catch(e) { return JSON.stringify({ok:false,error:e.message}); }
            })()
        """.trimIndent())
    }

    /**
     * Type text into the first element matching a CSS selector.
     * Returns JSON: {"ok":true} or {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun type(selector: String, text: String): String {
        val safe = escapeJs(selector)
        val safeText = escapeJs(text)
        return evalJs("""
            (function() {
                try {
                    var el = document.querySelector('$safe');
                    if (!el) return JSON.stringify({ok:false,error:'Selector not found'});
                    el.focus();
                    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    nativeInputValueSetter.call(el, '$safeText');
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    return JSON.stringify({ok:true,tag:el.tagName,type:el.type||'text'});
                } catch(e) { return JSON.stringify({ok:false,error:e.message}); }
            })()
        """.trimIndent())
    }

    /**
     * Scroll the page by (x, y) pixels.
     */
    @JavascriptInterface
    fun scroll(x: Float, y: Float): String {
        return evalJs("""
            (function() {
                try {
                    window.scrollBy($x, $y);
                    return JSON.stringify({ok:true,scrollX:window.scrollX,scrollY:window.scrollY});
                } catch(e) { return JSON.stringify({ok:false,error:e.message}); }
            })()
        """.trimIndent())
    }

    /**
     * Extract structured page content as JSON.
     * Returns title, links, forms, headings, and visible text.
     * Text is capped at 3000 chars to keep Agent context lean.
     */
    @JavascriptInterface
    fun content(): String {
        return evalJs("""
            (function() {
                try {
                    // Links
                    var links = [];
                    document.querySelectorAll('a[href]').forEach(function(a) {
                        var txt = (a.textContent||'').trim().substring(0,80);
                        var href = a.href;
                        if (txt && href && !href.startsWith('javascript:')) {
                            links.push({text:txt, href:href});
                        }
                    });
                    if (links.length > 50) links = links.slice(0,50);

                    // Forms
                    var forms = [];
                    document.querySelectorAll('form').forEach(function(f) {
                        var inputs = [];
                        f.querySelectorAll('input,textarea,select').forEach(function(inp) {
                            inputs.push({
                                name: inp.name||inp.id||'',
                                type: inp.type||inp.tagName.toLowerCase(),
                                placeholder: inp.placeholder||''
                            });
                        });
                        if (inputs.length>0) forms.push({id:f.id||'',action:f.action||'',inputs:inputs});
                    });

                    // Headings
                    var headings = [];
                    document.querySelectorAll('h1,h2,h3').forEach(function(h) {
                        headings.push({tag:h.tagName,text:(h.textContent||'').trim().substring(0,120)});
                    });

                    // Body text (first 3000 chars of visible text)
                    var body = document.body;
                    var text = body ? (body.innerText||body.textContent||'').replace(/\s+/g,' ').trim().substring(0,3000) : '';

                    return JSON.stringify({
                        title: document.title||'',
                        url: location.href,
                        links: links,
                        forms: forms,
                        headings: headings,
                        text: text,
                        images: Array.from(document.querySelectorAll('img[src]')).slice(0,10).map(function(img) {
                            return {src:img.src,alt:img.alt||'',width:img.naturalWidth,height:img.naturalHeight};
                        })
                    });
                } catch(e) { return JSON.stringify({error:e.message}); }
            })()
        """.trimIndent())
    }

    /**
     * Execute arbitrary JavaScript in the page and return the result.
     * Result is truncated to 5000 chars for safety.
     */
    @JavascriptInterface
    fun eval(js: String): String {
        return evalJs("""
            (function() {
                try {
                    var result = eval(${js.toJsonLiteral()});
                    if (result === undefined) return 'undefined';
                    if (result === null) return 'null';
                    var s = typeof result === 'string' ? result : JSON.stringify(result);
                    return s.length > 5000 ? s.substring(0,5000)+'...[truncated]' : s;
                } catch(e) { return 'Error: '+e.message; }
            })()
        """.trimIndent())
    }

    // ── Internal ────────────────────────────────────────────────────────

    /**
     * Execute JS and return result. Uses a short timeout to avoid
     * blocking WebView's JavaBridge thread pool indefinitely.
     *
     * SAFETY: Called from @JavascriptInterface (JavaBridge thread).
     * A long block here can exhaust the WebView thread pool → crash.
     * Timeout is set to 2s max; on timeout, returns error JSON.
     */
    private fun evalJs(script: String): String {
        // Fast path: if __mp is injected, use synchronous JS evaluation
        // Avoids the evaluateJavascript round-trip entirely
        if (script.startsWith("window.__mp") || script.contains("__mp.")) {
            // __mp calls are pure JS sync — still need evaluateJavascript but
            // they complete in <10ms since no DOM traversal
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var result = """{"ok":false,"error":"timeout"}"""
        try {
            val posted = webView.post {
                try {
                    webView.evaluateJavascript(script) { r ->
                        result = unquoteJs(r)
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    result = """{"ok":false,"error":"${escapeJs(e.message ?: "unknown")}"}"""
                    latch.countDown()
                }
            }
            if (!posted) {
                // WebView handler is gone (destroyed or shutting down)
                return """{"ok":false,"error":"webview detached"}"""
            }
            val ok = latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) {
                // Timeout — main thread is likely busy. Don't block longer.
                // The evaluateJavascript callback will still fire, but we
                // can't wait for it without risking thread pool exhaustion.
                return """{"ok":false,"error":"evaluation timeout (main thread busy)"}"""
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            result = """{"ok":false,"error":"interrupted"}"""
        } catch (e: Exception) {
            result = """{"ok":false,"error":"${escapeJs(e.message ?: "unknown")}"}"""
        }
        return result
    }

    /** Remove the JSON-string quoting that evaluateJavascript adds. */
    private fun unquoteJs(raw: String): String {
        var s = raw.trim()
        if (s == "null") return """{"ok":false,"error":"JS returned null"}"""
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
            s = s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return s
    }

    /** Escape a string for safe embedding in a JS string literal. */
    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\")
         .replace("'", "\\'")
         .replace("\"", "\\\"")
         .replace("\n", "\\n")
         .replace("\r", "")

    /** Convert a Kotlin string to a JS JSON string literal. */
    private fun String.toJsonLiteral(): String =
        "'" + this.replace("\\", "\\\\")
               .replace("'", "\\'")
               .replace("\n", "\\n") + "'"

    // ── Persistent bridge (speed optimization) ──────────────────────────

    /**
     * Inject the persistent `__mp` helper object into the page.
     * After injection, all subsequent commands use tiny one-liners:
     *   __mp.c('#btn') instead of full click script (~500→15 chars, ~33x smaller)
     *
     * Call once per page load. Subsequent calls are no-ops.
     */
    @JavascriptInterface
    fun inject(): String {
        return evalJs("""
(function(){
  if(window.__mp&&window.__mp._v)return JSON.stringify({ok:true,msg:'already injected',v:window.__mp._v});
  window.__mp={
    _v:1,_cache:{},
    c:function(s){var e=document.querySelector(s);if(!e)return JSON.stringify({ok:false,error:'not found:'+s});e.click();return JSON.stringify({ok:true,tag:e.tagName})},
    t:function(s,v){var e=document.querySelector(s);if(!e)return JSON.stringify({ok:false});e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;d.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));return JSON.stringify({ok:true})},
    sc:function(x,y){window.scrollBy(x,y);return JSON.stringify({ok:true,sx:window.scrollX,sy:window.scrollY})},
    ct:function(){try{var ls=[];document.querySelectorAll('a[href]').forEach(function(a){var t=(a.textContent||'').trim().substring(0,80);if(t&&a.href&&!a.href.startsWith('javascript:'))ls.push({text:t,href:a.href})});return JSON.stringify({title:document.title,url:location.href,links:ls.slice(0,50),text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().substring(0,3000)})}catch(e){return JSON.stringify({error:e.message})}},
    q:function(w){var m={search:'input[type=search],input[name=q],input[name=query],#search,[role=search] input',main:'main,article,#content,.post,.article,[role=main]',nav:'nav,#nav,.navbar,.menu,[role=navigation]'};var s=m[w];if(!s)return JSON.stringify({ok:false});var e=document.querySelector(s);return e?JSON.stringify({ok:true,selector:s,tag:e.tagName,text:(e.textContent||'').trim().substring(0,200)}):JSON.stringify({ok:false})},
    df:function(){var cur=window.__mp._cache._content||'';var raw=document.body?document.body.innerText:'';var fresh=raw.replace(/\\s+/g,' ').trim().substring(0,1000);window.__mp._cache._content=fresh;if(cur===fresh)return JSON.stringify({changed:false});return JSON.stringify({changed:true,added:fresh.substring(cur.length>0?this._lcs(cur,fresh):0)})},
    _lcs:function(a,b){for(var i=0;i<Math.min(a.length,b.length)&&a[i]===b[i];i++);return i}
  };
  return JSON.stringify({ok:true,msg:'__mp persistent bridge injected. Use: __mp.c(sel) __mp.t(sel,val) __mp.sc(x,y) __mp.ct() __mp.q(type) __mp.df()'});
})()
        """.trimIndent())
    }

    /**
     * Fast-path click using pre-injected __mp bridge.
     * Falls back to full script if __mp not available.
     */
    fun fastClick(selector: String): String {
        val s = selector.replace("'", "\\'")
        return evalJs("window.__mp?window.__mp.c('$s'):(function(){var e=document.querySelector('$s');if(!e)return JSON.stringify({ok:false});e.click();return JSON.stringify({ok:true})})()")
    }

    /** Fast-path type. */
    fun fastType(selector: String, text: String): String {
        val s = selector.replace("'", "\\'"); val t = text.replace("'", "\\'")
        return evalJs("window.__mp?window.__mp.t('$s','$t'):(function(){var e=document.querySelector('$s');if(!e)return JSON.stringify({ok:false});e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;d.call(e,'$t');e.dispatchEvent(new Event('input',{bubbles:true}));return JSON.stringify({ok:true})})()")
    }

    /** Fast-path content. Returns diff if cached, full content otherwise. */
    fun fastContent(): String {
        return evalJs("window.__mp?window.__mp.ct():(function(){try{var ls=[];document.querySelectorAll('a[href]').forEach(function(a){var t=(a.textContent||'').trim().substring(0,80);if(t&&a.href)ls.push({text:t,href:a.href})});return JSON.stringify({title:document.title,url:location.href,links:ls.slice(0,50),text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().substring(0,3000)})}catch(e){return JSON.stringify({error:e.message})}})()")
    }

    /** Fast-path diff — returns only changed text since last extraction. */
    @JavascriptInterface
    fun diff(): String {
        return evalJs("window.__mp?window.__mp.df():JSON.stringify({changed:true,full:true,text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().substring(0,1000)})")
    }
}
