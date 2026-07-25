---
name: browser-v0.5.0-optimization
description: MP浏览器 v0.5.0 优化 — 暗色模式/页面查找/阅读模式/翻译/SSL/下载/错误页
metadata:
  type: project
---

## MP 浏览器 v0.5.0 优化 (2026-07-25)

**Why:** BrowserActivity.kt 约 1218 行，暗色模式硬编码 false，翻译仅翻译标题 (FIX B39)，SSL 错误无提示，无页面查找/阅读模式。

**变更:**
- 暗色模式：`Configuration.uiMode` 跟随系统（与 Shell 一致）
- 页面查找：`BrowserFindBar.kt` 组件 + `WebView.findAllAsync/findNext` + 工具栏查找按钮
- 阅读模式：`BrowserReaderMode.kt` 组件 + JS 提取 `<article>/#content/.post/body` 内容 + 对话框大字宽行距渲染
- 完整翻译 (FIX B39)：JS `evaluateJavascript` 注入提取 `document.body.innerText`，分段 5000 字符翻译
- SSL 证书错误：`Toast` 提示用户（替代静默 cancel）
- 下载监听：`WebView.setDownloadListener` → Intent 启动系统下载
- 错误页面：`onReceivedError` 自定义 HTML 含重试按钮
- 重复 import 移除，新增 `POST_NOTIFICATIONS` 权限
- versionCode 6→7, versionName 0.4.0→0.5.0

**How to apply:** BrowserActivity.kt 保留原始结构（未做文件拆分），新增 2 个 UI 组件文件（BrowserFindBar.kt, BrowserReaderMode.kt），ProGuard 规则已包含 `com.mengpaw.browser.ui.**`。

**文件:**
- `mengpaw-browser/src/main/kotlin/com/mengpaw/browser/BrowserActivity.kt`
- `mengpaw-browser/src/main/kotlin/com/mengpaw/browser/ui/BrowserFindBar.kt` (NEW)
- `mengpaw-browser/src/main/kotlin/com/mengpaw/browser/ui/BrowserReaderMode.kt` (NEW)
- `mengpaw-browser/build.gradle.kts` (version bump)
- `mengpaw-browser/src/main/AndroidManifest.xml` (POST_NOTIFICATIONS)
- `mengpaw-browser/proguard-rules.pro` (keep rules for ui.**)
