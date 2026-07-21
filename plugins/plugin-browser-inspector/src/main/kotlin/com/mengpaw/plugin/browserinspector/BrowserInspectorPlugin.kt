// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.browserinspector

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * Web Development Toolkit — element inspector with annotations.
 *
 * Injects a visual element selector into the page that lets Agent:
 * - Hover to highlight elements (blue glow)
 * - Click to select and inspect (red border)
 * - Add notes/annotations to selected elements
 * - Export an annotated element map for precise DOM targeting
 *
 * ## Workflow
 * 1. browser.open <dev-server-url>
 * 2. inspector.start          → activates selection mode
 * 3. (User/Agent clicks elements) → inspector.list shows selected
 * 4. inspector.annotate "#header" "需要改为flex布局"
 * 5. inspector.export          → Agent gets annotated element map
 * 6. Agent uses browser.eval to modify targeted elements
 * 7. inspector.stop            → cleanup
 */
class BrowserInspectorPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "browser-inspector-plugin",
        name = "网页开发套件",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "元素选择器+批注：悬停高亮、点击选择、添加备注、导出元素地图",
        permissions = emptyList(),
        minCoreVersion = "0.2.3",
        commands = listOf("inspector.start", "inspector.stop", "inspector.select",
            "inspector.annotate", "inspector.list", "inspector.export")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "start" to ::start,
        "stop" to ::stop,
        "select" to ::select,
        "annotate" to ::annotate,
        "list" to ::listAnnotations,
        "export" to ::export,
    )

    // ── inspector.start ─────────────────────────────────────────────────

    private suspend fun start(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
## 网页开发套件 — 元素选择器

将以下两段代码注入页面以激活选择器:

### 1. 注入 CSS (高亮样式)
使用 browser.eval 执行:
```js
${INSPECTOR_CSS_JS.trimIndent()}
```

### 2. 注入 JS (选择器逻辑)
使用 browser.eval 执行:
```js
${INSPECTOR_JS.trimIndent()}
```

### 使用方式
- 鼠标悬停元素 → 蓝色高亮 + 信息浮层
- 点击元素 → 红色选中 + 自动复制选择器
- inspector.list → 查看已选中元素
- inspector.annotate <选择器> <备注> → 添加批注
- inspector.export → 导出完整元素地图
- inspector.stop → 移除选择器
""".trimIndent())
    }

    // ── inspector.stop ──────────────────────────────────────────────────

    private suspend fun stop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
使用 browser.eval 执行以下代码移除选择器:
```js
try {
  var el=document.getElementById('__mp_inspector_overlay'); if(el)el.remove();
  var el2=document.getElementById('__mp_inspector_tooltip'); if(el2)el2.remove();
  var el3=document.getElementById('__mp_inspector_css'); if(el3)el3.remove();
  document.removeEventListener('mouseover',window.__mpInspectorHover,true);
  document.removeEventListener('click',window.__mpInspectorClick,true);
  delete window.__mpInspectorAnnotations;
  delete window.__mpInspectorHover;
  delete window.__mpInspectorClick;
  JSON.stringify({ok:true,msg:'Inspector removed'});
} catch(e) { JSON.stringify({ok:false,error:e.message}) }
```
""".trimIndent())
    }

    // ── inspector.select ────────────────────────────────────────────────

    private suspend fun select(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: inspector.select <css-selector>\n可通过 browser.eval 获取悬停时复制的选择器。",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val selector = args[0]
        val safe = selector.replace("'", "\\'")
        return ExecutionResult.ok("""
使用 browser.eval 执行以下代码高亮并分析元素:
```js
(function(){
  var el=document.querySelector('$safe');
  if(!el) return JSON.stringify({ok:false,error:'Selector not found'});
  var rect=el.getBoundingClientRect();
  var info={
    tag:el.tagName.toLowerCase(),
    id:el.id||'',
    classes:Array.from(el.classList).join(' '),
    rect:{x:Math.round(rect.x),y:Math.round(rect.y),w:Math.round(rect.width),h:Math.round(rect.height)},
    attrs:{};
    for(var a of el.attributes){if(a.name!=='class'&&a.name!=='id')info.attrs[a.name]=a.value}
    text:(el.textContent||'').trim().substring(0,100),
    html:el.outerHTML.substring(0,500)
  };
  // Highlight
  el.style.outline='3px solid #F53F3F';el.style.outlineOffset='2px';
  // Store
  if(!window.__mpInspectorAnnotations)window.__mpInspectorAnnotations={};
  window.__mpInspectorAnnotations['$safe']=info;
  return JSON.stringify({ok:true,element:info,selector:'$safe'});
})()
```
""".trimIndent())
    }

    // ── inspector.annotate ──────────────────────────────────────────────

    private suspend fun annotate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: inspector.annotate <css-selector> <note>",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val selector = args[0]; val note = args.drop(1).joinToString(" ")
        val safe = selector.replace("'", "\\'"); val safeNote = note.replace("'", "\\'").replace("`", "\\`")
        return ExecutionResult.ok("""
使用 browser.eval 执行以下代码添加批注:
```js
(function(){
  var el=document.querySelector('$safe');
  if(!el) return JSON.stringify({ok:false,error:'not found'});
  if(!window.__mpInspectorAnnotations)window.__mpInspectorAnnotations={};
  window.__mpInspectorAnnotations['$safe']=window.__mpInspectorAnnotations['$safe']||{};
  window.__mpInspectorAnnotations['$safe'].note='$safeNote';
  window.__mpInspectorAnnotations['$safe'].annotatedAt=new Date().toISOString();
  // Visual badge
  var badge=document.createElement('div');
  badge.textContent='📝';
  badge.title='$safeNote';
  badge.style.cssText='position:absolute;top:-12px;right:-8px;background:#165DFF;color:#fff;font-size:12px;padding:2px 6px;border-radius:8px;z-index:999999;cursor:help';
  badge.setAttribute('data-mp-annotation','true');
  el.style.position=el.style.position||'relative';
  el.appendChild(badge);
  return JSON.stringify({ok:true,selector:'$safe',note:'$safeNote'});
})()
```
""".trimIndent())
    }

    // ── inspector.list ──────────────────────────────────────────────────

    private suspend fun listAnnotations(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
使用 browser.eval 执行以下代码列出所有已批注元素:
```js
(function(){
  if(!window.__mpInspectorAnnotations||Object.keys(window.__mpInspectorAnnotations).length===0)
    return JSON.stringify({count:0,message:'No annotated elements. Use inspector.select or click elements.'});
  var items=[];
  for(var sel in window.__mpInspectorAnnotations){
    var a=window.__mpInspectorAnnotations[sel];
    items.push({selector:sel,tag:a.tag||'?',note:a.note||'(无备注)',annotatedAt:a.annotatedAt||''});
  }
  return JSON.stringify({count:items.length,elements:items});
})()
```
""".trimIndent())
    }

    // ── inspector.export ────────────────────────────────────────────────

    private suspend fun export(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("""
使用 browser.eval 执行以下代码导出完整元素地图:
```js
(function(){
  if(!window.__mpInspectorAnnotations)
    return JSON.stringify({error:'No inspector data'});
  return JSON.stringify({
    exportedAt:new Date().toISOString(),
    url:location.href,
    title:document.title,
    elements:window.__mpInspectorAnnotations
  });
})()
```

导出后，Agent 可以通过选择器精确操作元素:
- browser.click "$selector"
- browser.type "$selector" "新内容"
- browser.eval "document.querySelector('$selector').style.color='red'"
""".trimIndent())
    }

    // ── Injection scripts ───────────────────────────────────────────────

    companion object {
        /** CSS styles for element hover/select highlighting. Injected via browser.eval. */
        val INSPECTOR_CSS_JS = """
(function(){
  if(document.getElementById('__mp_inspector_css'))return JSON.stringify({ok:true,msg:'already active'});
  var css=document.createElement('style');
  css.id='__mp_inspector_css';
  css.textContent=`
    .__mp_hover{outline:2px solid #165DFF!important;outline-offset:1px!important;background:rgba(22,93,255,0.05)!important;transition:outline 0.1s}
    .__mp_selected{outline:3px solid #F53F3F!important;outline-offset:2px!important}
    #__mp_inspector_tooltip{position:fixed;background:#1D2129;color:#FFF;font:12px monospace;padding:6px 10px;border-radius:4px;z-index:9999999;pointer-events:none;max-width:400px;white-space:pre-wrap;box-shadow:0 4px 12px rgba(0,0,0,0.3)}
    #__mp_inspector_tooltip .mp-tag{color:#F53F3F}
    #__mp_inspector_tooltip .mp-id{color:#FFB400}
    #__mp_inspector_tooltip .mp-cls{color:#00B42A}
    #__mp_inspector_tooltip .mp-dim{color:#86909C}
  `;
  document.head.appendChild(css);
  return JSON.stringify({ok:true,msg:'CSS injected'});
})()
""".trimIndent()

        /** Main inspector JS: hover/click handlers + annotation storage. */
        val INSPECTOR_JS = """
(function(){
  if(window.__mpInspectorActive)return JSON.stringify({ok:true,msg:'already active'});
  window.__mpInspectorActive=true;
  window.__mpInspectorAnnotations=window.__mpInspectorAnnotations||{};

  // Tooltip element
  var tip=document.createElement('div');
  tip.id='__mp_inspector_tooltip';document.body.appendChild(tip);

  // Hover handler
  window.__mpInspectorHover=function(e){
    var el=e.target;
    if(el===tip||el.closest('#__mp_inspector_tooltip'))return;
    // Remove previous hover
    var prev=document.querySelector('.__mp_hover');
    if(prev&&prev!==el){prev.classList.remove('__mp_hover');prev.removeAttribute('data-mp-hovered')}
    if(el.classList.contains('__mp_hover'))return;
    el.classList.add('__mp_hover');el.setAttribute('data-mp-hovered','true');
    // Build tooltip
    var tag=el.tagName.toLowerCase();
    var id=el.id?'#'+el.id:'';
    var cls=Array.from(el.classList).filter(function(c){return c!=='__mp_hover'&&c!=='__mp_selected'}).slice(0,3).join('.');
    if(cls)cls='.'+cls;
    var sel=tag+id+cls;
    var rect=el.getBoundingClientRect();
    var dims=Math.round(rect.width)+'×'+Math.round(rect.height);
    tip.innerHTML='<span class=mp-tag>'+sel+'</span><br><span class=mp-dim>'+dims+'</span>';
    if(window.__mpInspectorAnnotations[sel]&&window.__mpInspectorAnnotations[sel].note){
      tip.innerHTML+='<br>📝 '+window.__mpInspectorAnnotations[sel].note;
    }
    var x=e.clientX+12,y=e.clientY-12;
    if(x+420>window.innerWidth)x=e.clientX-420;
    if(y<40)y=e.clientY+20;
    tip.style.left=x+'px';tip.style.top=y+'px';
  };

  // Click handler
  window.__mpInspectorClick=function(e){
    e.preventDefault();e.stopPropagation();
    var el=e.target;
    if(el===tip||el.closest('#__mp_inspector_tooltip'))return;
    el.classList.add('__mp_selected');
    var tag=el.tagName.toLowerCase();
    var id=el.id?'#'+el.id:'';
    var cls=Array.from(el.classList).filter(function(c){return c!=='__mp_hover'&&c!=='__mp_selected'}).slice(0,3).join('.');
    if(cls)cls='.'+cls;
    var sel=tag+id+cls;
    window.__mpInspectorAnnotations[sel]=window.__mpInspectorAnnotations[sel]||{};
    var a=window.__mpInspectorAnnotations[sel];
    a.tag=tag;a.id=el.id||'';a.classes=Array.from(el.classList).filter(function(c){return c!=='__mp_hover'&&c!=='__mp_selected'}).join(' ');
    a.selectedAt=new Date().toISOString();
    tip.innerHTML='<span style=color:#F53F3F>✓ SELECTED</span><br><span class=mp-tag>'+sel+'</span><br><span class=mp-dim>Selector copied. Use inspector.annotate "'+sel.replace(/"/g,'\\"')+'" "备注"</span>';
    return false;
  };

  document.addEventListener('mouseover',window.__mpInspectorHover,true);
  document.addEventListener('click',window.__mpInspectorClick,true);

  return JSON.stringify({ok:true,msg:'Inspector active. Hover=highlight, Click=select.',active:true});
})()
""".trimIndent()
    }
}
