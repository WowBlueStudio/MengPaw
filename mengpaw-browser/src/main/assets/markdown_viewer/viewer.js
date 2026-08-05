/* MP 浏览器 Markdown 预览交互 (md-reader 移植): lang 标签 / 复制按钮 / 图片放大 */
(function () {
  "use strict";

  function ready(fn) {
    if (document.readyState !== "loading") { fn(); } else {
      document.addEventListener("DOMContentLoaded", fn);
    }
  }

  /* ── 语法高亮 (hljs v11) ── */
  function initHighlight() {
    if (window.hljs) {
      try { hljs.highlightAll(); } catch (e) { /* 单块失败不阻塞整体 */ }
    }
  }

  /* ── lang 标签: code.language-* → pre[data-lang] → CSS ::before 显示 ── */
  function initLangLabels() {
    document.querySelectorAll(".md-body pre > code.hljs[class*='language-']").forEach(function (code) {
      var m = /language-([\w-]+)/.exec(code.className);
      if (m && code.parentElement && !code.parentElement.getAttribute("data-lang")) {
        code.setAttribute("lang", m[1]);
      }
    });
  }

  /* ── 复制按钮: clipboard API + execCommand fallback (file:// 下 clipboard 不可用) ── */
  function fallbackCopy(text) {
    var ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok;
  }

  function copyText(text) {
    if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text).then(function () { return true; }).catch(function () {
        return fallbackCopy(text);
      });
    }
    return Promise.resolve(fallbackCopy(text));
  }

  function initCopyButtons() {
    var COPY_SVG = '<svg class="icon-copy" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg"><path d="M5 3a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V3zm7 0H7v4h5V3zM3 6a2 2 0 0 0-2 2v5a2 2 0 0 0 2 2h5a2 2 0 0 0 2-2v-1H7a3 3 0 0 1-3-3V6H3z"/></svg>' +
      '<svg class="icon-success" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg"><path d="M6.5 11.8 2.7 8l1.2-1.2 2.6 2.6 5.6-5.6L13.3 5 6.5 11.8z"/></svg>';

    document.querySelectorAll(".md-body pre > code.hljs").forEach(function (code) {
      var pre = code.parentElement;
      if (!pre || pre.querySelector(".copy-btn")) return;
      var btn = document.createElement("button");
      btn.className = "copy-btn";
      btn.title = "Copy";
      btn.innerHTML = COPY_SVG;
      btn.addEventListener("click", function () {
        if (btn.classList.contains("copied")) return;
        copyText(code.innerText).then(function () {
          btn.classList.add("copied");
          setTimeout(function () { btn.classList.remove("copied"); }, 1000);
        });
      });
      pre.appendChild(btn);
    });
  }

  /* ── 图片点击放大: body 级委托 + backdrop blur 模态 ── */
  function initImageZoom() {
    var modal = null;
    document.addEventListener("click", function (e) {
      var t = e.target;
      if (t && t.tagName === "IMG" && !t.closest("a") && !t.closest(".img-modal")) {
        e.preventDefault();
        if (!modal) {
          modal = document.createElement("div");
          modal.className = "img-modal";
          modal.addEventListener("click", function () { modal.classList.remove("opened"); });
          document.body.appendChild(modal);
        }
        modal.innerHTML = "";
        var img = new Image();
        img.src = t.src;
        img.alt = t.alt || "";
        modal.appendChild(img);
        modal.classList.add("opened");
      }
    });
  }

  /* ── 目录抽屉 (md-reader table-of-contents 导航移植): slugify / 构建 / 交互 / 滚动高亮 ──
     结构对齐 markdown-it-table-of-contents: 嵌套 ul 层级 + a[href="#slug"]。
     非目标: [[toc]] 标记解析 / 抽屉内搜索过滤 / 跨会话状态记忆。 */
  var tocState = null;   // { headings, linkBySlug, currentLink }
  var scrollTicking = false;

  /* slugify: 小写 + 标点/符号→- + CJK/假名/谚文保留。
     与 mToc 默认 slugify (percent 编码标点) 的有意偏差: 标点→- 更可读;
     跳转走 getElementById, 编码差异无功能影响。 */
  function slugify(text) {
    var s = String(text).trim().toLowerCase()
      .replace(/[^\w一-鿿぀-ヿ가-힯]+/g, "-")
      .replace(/^-+|-+$/g, "");
    return s || "section";
  }

  /* 遍历 .md-body h1-h6 (含 blockquote 内标题) → 唯一 slug id + 嵌套 ul 树。
     层级跳变 (h1→h3) 直接嵌套不补层, 与 markdown-it-table-of-contents 一致。 */
  function buildToc() {
    var headings = Array.prototype.slice.call(
      document.querySelectorAll(".md-body h1, .md-body h2, .md-body h3, .md-body h4, .md-body h5, .md-body h6")
    );
    if (!headings.length) return null;
    var used = {};
    var linkBySlug = {};
    var root = document.createElement("ul");
    var stack = [{ level: 0, ul: root }];
    for (var i = 0; i < headings.length; i++) {
      var h = headings[i];
      var text = (h.textContent || "").trim();
      var base = slugify(text);
      var slug = base, n = 2;
      while (used[slug]) { slug = base + "-" + n; n++; }
      used[slug] = true;
      h.id = slug;
      var level = parseInt(h.tagName.charAt(1), 10);
      while (stack.length > 1 && stack[stack.length - 1].level >= level) stack.pop();
      var li = document.createElement("li");
      var a = document.createElement("a");
      a.href = "#" + slug;
      a.textContent = text;
      li.appendChild(a);
      var ul = document.createElement("ul");
      li.appendChild(ul);
      stack[stack.length - 1].ul.appendChild(li);
      stack.push({ level: level, ul: ul });
      linkBySlug[slug] = a;
    }
    // 收尾删空 ul (最深一级无子节点时)
    for (var j = 0; j < stack.length; j++) {
      var u = stack[j].ul;
      if (u && !u.children.length && u.parentNode) u.parentNode.removeChild(u);
    }
    return { root: root, headings: headings, linkBySlug: linkBySlug };
  }

  function closeToc() {
    document.body.classList.remove("toc-open");
  }

  function openToc() {
    document.body.classList.add("toc-open");
    updateActiveToc();
    var act = document.querySelector(".toc-panel a.active");
    if (act && act.scrollIntoView) act.scrollIntoView({ block: "nearest" });
  }

  /* 滚动高亮: 判定线 64px (避开顶部悬浮按钮), 逆序取最后过线标题;
     滚到底兜底最后一项, 文档头部兜底第一项; 命中项才切 class 避免无谓 DOM 写。 */
  var TOC_LINE = 64;
  function updateActiveToc() {
    if (!tocState || !tocState.headings.length) return;
    var headings = tocState.headings;
    var active = null;
    for (var i = headings.length - 1; i >= 0; i--) {
      if (headings[i].getBoundingClientRect().top <= TOC_LINE) { active = headings[i]; break; }
    }
    if (!active) {
      var doc = document.documentElement;
      var atBottom = window.innerHeight + window.scrollY >= doc.scrollHeight - 4;
      active = atBottom ? headings[headings.length - 1] : headings[0];
    }
    var a = tocState.linkBySlug[active.id];
    if (a !== tocState.currentLink) {
      if (tocState.currentLink) tocState.currentLink.classList.remove("active");
      if (a) a.classList.add("active");
      tocState.currentLink = a;
    }
  }

  /* rAF 节流 scroll — 打开态 overflow:hidden 锁滚动, scroll 不触发,
     故高亮靠 openToc/resize 主动重算, 关闭态滚动仅预热状态 */
  function onScrollToc() {
    if (scrollTicking) return;
    scrollTicking = true;
    window.requestAnimationFrame(function () {
      updateActiveToc();
      scrollTicking = false;
    });
  }

  function initToc() {
    var built = buildToc();
    if (!built) return;  // 无标题 → 三节点保持 hidden, 按钮永不出现
    tocState = { headings: built.headings, linkBySlug: built.linkBySlug, currentLink: null };
    var btn = document.getElementById("toc-btn");
    var mask = document.getElementById("toc-mask");
    var panel = document.getElementById("toc-panel");
    btn.hidden = false;
    mask.hidden = false;
    panel.hidden = false;
    document.getElementById("toc-body").appendChild(built.root);

    btn.addEventListener("click", function () {
      if (document.body.classList.contains("toc-open")) closeToc(); else openToc();
    });
    mask.addEventListener("click", closeToc);
    document.getElementById("toc-close").addEventListener("click", closeToc);
    // 委托: 大纲项 → 平滑滚动 + 关闭 (绝不走 #hash 导航 — WebView 拦截非 http(s))
    document.getElementById("toc-body").addEventListener("click", function (e) {
      var a = e.target && e.target.closest ? e.target.closest('a[href^="#"]') : null;
      if (!a) return;
      e.preventDefault();
      var slug = a.getAttribute("href").slice(1);
      var h = document.getElementById(slug);
      closeToc();
      if (h) h.scrollIntoView({ behavior: "smooth", block: "start" });
    });
    window.addEventListener("scroll", onScrollToc, { passive: true });
    window.addEventListener("resize", updateActiveToc);
    updateActiveToc();
  }

  ready(function () {
    initHighlight();
    initLangLabels();
    initCopyButtons();
    initImageZoom();
    initToc();
  });
})();
