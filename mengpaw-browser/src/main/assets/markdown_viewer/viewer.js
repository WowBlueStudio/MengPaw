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

  ready(function () {
    initHighlight();
    initLangLabels();
    initCopyButtons();
    initImageZoom();
  });
})();
