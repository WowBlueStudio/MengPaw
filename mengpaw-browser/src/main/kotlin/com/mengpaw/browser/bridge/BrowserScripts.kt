// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

/**
 * 页面 JS 脚本常量（自 BrowserBridge 拆出 — 400 行文件拆分批次 2）。
 * 纯字符串生成 — 无状态, 单测可直接断言脚本内容。
 */

/** 结构化内容提取脚本 — title/links/forms/headings/text/images。 */
internal fun contentScript(): String = """
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
""".trimIndent()

/**
 * 持久 `__mp` 桥注入脚本 — 注入后所有命令走 ~15 字符快速通道 (~33x 更小数据量)。
 * 幂等: 已注入则原样返回。
 */
internal fun injectScript(): String = """
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
""".trimIndent()

/** __mp 未注入时的点击 fallback（__mp 可用则走快速通道）。 */
internal fun fastClickScript(selector: String): String =
    "window.__mp?window.__mp.c('$selector'):(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false});e.click();return JSON.stringify({ok:true})})()"

/** __mp 未注入时的输入 fallback。 */
internal fun fastTypeScript(selector: String, text: String): String =
    "window.__mp?window.__mp.t('$selector','$text'):(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false});e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;d.call(e,'$text');e.dispatchEvent(new Event('input',{bubbles:true}));return JSON.stringify({ok:true})})()"

/** __mp 未注入时的内容提取 fallback。 */
internal fun fastContentScript(): String =
    "window.__mp?window.__mp.ct():(function(){try{var ls=[];document.querySelectorAll('a[href]').forEach(function(a){var t=(a.textContent||'').trim().substring(0,80);if(t&&a.href)ls.push({text:t,href:a.href})});return JSON.stringify({title:document.title,url:location.href,links:ls.slice(0,50),text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().substring(0,3000)})}catch(e){return JSON.stringify({error:e.message})}})()"

/** __mp 未注入时的 diff fallback。 */
internal fun diffScript(): String =
    "window.__mp?window.__mp.df():JSON.stringify({changed:true,full:true,text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().substring(0,1000)})"

// ── 元素交互脚本 (单行) ──────────────────────────────────────────────

/** 点击指定选择器首个元素。 */
internal fun clickScript(selector: String): String = """
    (function() {
        try {
            var el = document.querySelector('$selector');
            if (!el) return JSON.stringify({ok:false,error:'Selector not found: $selector'});
            el.click();
            return JSON.stringify({ok:true,tag:el.tagName,text:(el.textContent||'').trim().substring(0,100)});
        } catch(e) { return JSON.stringify({ok:false,error:e.message}); }
    })()
""".trimIndent()

/** 输入文本 (native setter + input 事件)。 */
internal fun typeScript(selector: String, text: String): String = """
    (function() {
        try {
            var el = document.querySelector('$selector');
            if (!el) return JSON.stringify({ok:false,error:'Selector not found'});
            el.focus();
            var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
            nativeInputValueSetter.call(el, '$text');
            el.dispatchEvent(new Event('input', {bubbles:true}));
            return JSON.stringify({ok:true,tag:el.tagName,type:el.type||'text'});
        } catch(e) { return JSON.stringify({ok:false,error:e.message}); }
    })()
""".trimIndent()

/** 滚动 (x, y) 像素。 */
internal fun scrollScript(x: Float, y: Float): String = """
    (function() {
        try {
            window.scrollBy($x, $y);
            return JSON.stringify({ok:true,scrollX:window.scrollX,scrollY:window.scrollY});
        } catch(e) { return JSON.stringify({ok:false,error:e.message}); }
    })()
""".trimIndent()

/** 同步检查选择器是否出现在 DOM (waitForSelector 轮询用 — 每次调用立即返回)。 */
internal fun waitForSelectorCheckScript(selector: String): String = """
(function(){try{
  var el=document.querySelector('$selector');
  if(el)return JSON.stringify({ok:true,found:true,tag:el.tagName,visible:!!(el.offsetParent)});
  return JSON.stringify({ok:false,error:'not found yet'});
}catch(e){return JSON.stringify({ok:false,error:e.message});}})()
""".trimIndent()

/** 按键派发脚本 — page.key <key> 用。特殊键走映射表，单字符按 ASCII。 */
internal fun keyScript(key: String): String = """
    (function(){
        var k = '$key';
        var map = {Enter:13, Tab:9, Escape:27, Backspace:8, Delete:46,
            ArrowUp:38, ArrowDown:40, ArrowLeft:37, ArrowRight:39,
            Home:36, End:35, PageUp:33, PageDown:34};
        var keyCode = map[k] || (k.length === 1 ? k.toUpperCase().charCodeAt(0) : 0);
        var el = document.activeElement || document.body;
        var opts = {key:k, keyCode:keyCode, which:keyCode, code:k, bubbles:true, cancelable:true};
        el.dispatchEvent(new KeyboardEvent('keydown', opts));
        el.dispatchEvent(new KeyboardEvent('keypress', opts));
        el.dispatchEvent(new KeyboardEvent('keyup', opts));
        return JSON.stringify({ok:true, key:k, tag:el.tagName});
    })()
""".trimIndent()

/** localStorage/sessionStorage get。 */
internal fun storageGetScript(storageType: String, key: String): String =
    "(function(){try{var v=${storageType}.getItem('$key');return v?JSON.stringify({ok:true,value:v}):JSON.stringify({ok:false,error:'not found'});}catch(e){return JSON.stringify({ok:false,error:e.message});}})()"

/** localStorage/sessionStorage set。 */
internal fun storageSetScript(storageType: String, key: String, value: String): String =
    "(function(){try{${storageType}.setItem('$key','$value');return JSON.stringify({ok:true});}catch(e){return JSON.stringify({ok:false,error:e.message});}})()"

/** localStorage/sessionStorage clear。 */
internal fun storageClearScript(storageType: String): String =
    "(function(){try{${storageType}.clear();return JSON.stringify({ok:true});}catch(e){return JSON.stringify({ok:false,error:e.message});}})()"

/** 取元素属性。 */
internal fun attrScript(selector: String, attribute: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false,error:'not found'});return JSON.stringify({ok:true,value:e.getAttribute('$attribute')||''});})()"

/** 取元素文本。 */
internal fun textScript(selector: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false,error:'not found'});return JSON.stringify({ok:true,text:(e.textContent||'').trim().substring(0,2000)});})()"

/** 可见性检查。 */
internal fun visibleScript(selector: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false,error:'not found'});var r=e.getBoundingClientRect();var s=getComputedStyle(e);return JSON.stringify({ok:true,visible:!!(r.width&&r.height&&s.display!=='none'&&s.visibility!=='hidden')});})()"

/** 启用状态检查。 */
internal fun enabledScript(selector: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false,error:'not found'});return JSON.stringify({ok:true,enabled:!e.disabled});})()"

/** select 元素选值。 */
internal fun selectScript(selector: String, value: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false,error:'not found'});e.value='$value';e.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({ok:true,value:'$value'});})()"

/** 提交表单。 */
internal fun submitScript(selector: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e||e.tagName!=='FORM')return JSON.stringify({ok:false,error:'not a form'});e.submit();return JSON.stringify({ok:true});})()"

/** 勾选。 */
internal fun checkScript(selector: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false,error:'not found'});e.checked=true;e.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({ok:true});})()"

/** 取消勾选。 */
internal fun uncheckScript(selector: String): String =
    "(function(){var e=document.querySelector('$selector');if(!e)return JSON.stringify({ok:false,error:'not found'});e.checked=false;e.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({ok:true});})()"
