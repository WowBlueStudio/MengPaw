---
name: browser-debug
description: 浏览器控制调试与故障排查指南 — 常见错误、可视化调试、WebView 限制
enabled: true
category: browser
---
# 浏览器调试与故障排查

## 常见错误及解决方案

| 错误 | 可能原因 | 解决方案 |
|------|----------|----------|
| `selector not found` | 选择器路径错误 / 元素未加载 / 在 iframe 中 | 检查选择器；增加 `browser.wait.selector`；检查是否在 iframe 内 |
| `timeout` | 页面加载慢 / 网络阻塞 / 元素未出现 | 增加超时值；检查网络状态；`browser.screenshot` 确认页面状态 |
| `SSL error` | 证书过期 / 自签名证书 / 中间人攻击 | 确认 URL 使用 HTTPS；WebView 默认信任系统证书 |
| `navigation failure` | 重定向链断裂 / DNS 解析失败 / 无网络 | 检查网络连通性；`browser.open` 使用绝对 URL |
| `element not visible` | 元素被隐藏 / 被覆盖 / 在折叠区外 | 先 `browser.scroll to <sel>` 再点击；检查 CSS `display:none` |
| `element not enabled` | 元素 disabled / 只读 / 被遮罩层挡住 | 检查元素属性；等待条件满足后再操作 |
| `JavaScript error` | eval 代码有语法错误 / DOM API 不存在 | 在页面控制台试运行 JS 确认；注意 WebView 不支持部分 ES6+ 语法 |
| `blank page` | 页面白屏 / JS 未加载 / CSP 拦截 | `browser.content` 查看实际 HTML；检查是否有 CSP 报错 |

## 可视化调试三板斧

### 1. 截图确认页面状态
```
browser.screenshot "debug-before.png"
browser.click "#button"
browser.wait 1000
browser.screenshot "debug-after.png"
```
对比截图可以发现元素位置、可见性、页面跳转等问题。

### 2. eval 探查 DOM 状态
```
browser.eval "document.querySelector('#my-element')?.outerHTML"
browser.eval "document.readyState"                     # loading / interactive / complete
browser.eval "window.innerWidth + 'x' + window.innerHeight"
browser.eval "navigator.userAgent"
browser.eval "JSON.stringify(Object.keys(window))"     # 检查全局变量
```

### 3. 快速查询元素
```
browser.q ".suspicious-element"
# 返回：{ tag, id, classes, text, visible, rect }
```

## 控制台日志捕获

通过 eval 劫持 console 方法捕获日志：
```
browser.eval "(function() {
  const origLog = console.log;
  const origErr = console.error;
  window.__logs = [];
  console.log = (...args) => { window.__logs.push(['log', ...args]); origLog.apply(console, args); };
  console.error = (...args) => { window.__logs.push(['error', ...args]); origErr.apply(console, args); };
})()"

# 执行一些操作后获取日志
let $logs = browser.eval "JSON.stringify(window.__logs)"
```

## CSP / CORS 问题处理

### CSP 导致注入失败
```
# CSP 可能阻止 browser.inject 注入的脚本
# 变通方案：使用 eval 替代 inject
browser.eval "var s=document.createElement('style');s.textContent='body{background:red}';document.head.appendChild(s)"
```

### CORS 限制 Ajax 请求
```
# 页面中的 XHR 受 CORS 限制
# 变通：使用 MengPaw 的 net.get/net.post 代替页面内请求
net.get "https://api.example.com/data"    # 走 MengPaw 网络层，不受 CORS 限制
```

## Cookie / Storage 问题

### Cookie 未生效
```
browser.cookies                              # 检查当前 cookies
browser.cookies.set "key=value" ".domain"    # 设置时 domain 需匹配
```

### localStorage 检查
```
browser.eval "JSON.stringify(localStorage)"
browser.eval "localStorage.getItem('token')"
browser.storage local "preferences"
```

### 跨域 Cookie 问题
```
# WebView 中跨域请求默认不携带第三方 cookie
# 注意：Android WebView 需额外配置 setAcceptThirdPartyCookies
# browser.* 命令无法修改 WebView 配置，需在宿主应用中设置
```

## WebView 限制 vs 桌面浏览器

| 功能 | Android WebView | 桌面浏览器 |
|------|----------------|------------|
| 视口 | 设备屏幕尺寸 | 可任意设置 |
| User-Agent | 含 `wv` 标记 | 标准 UA |
| 文件上传 | 仅支持部分 input 类型 | 完整支持 |
| 多标签页 | 同一容器内管理 | 独立进程 |
| WebSocket | 支持 | 支持 |
| ES6+ 语法 | 取决于系统 WebView 版本 | 最新 |
| WebRTC | 有限支持 | 完整支持 |
| IndexedDB | 支持 | 支持 |
| Service Worker | 默认不开启 | 完整支持 |
| GPU 加速 | 依赖设备硬件 | 完整 |
| 打印 (PDF) | 不支持 | 支持 |
| 浏览器扩展 | 不支持 | 支持 |
| 远程调试 | Chrome DevTools 可连接模拟器 | 直接 DevTools |

## 调试检查清单

遇到问题时依次执行：
1. `browser.screenshot` — 页面长什么样？
2. `browser.content` — HTML 结构是否和预期一致？
3. `browser.eval "document.readyState"` — 页面加载完了吗？
4. `browser.url` — 实际 URL 是哪个？
5. `browser.cookies` — Cookie 状态正常吗？
6. `browser.viewport 375x812` — 视口设置是否正确？
7. 检查 Android 系统 WebView 版本是否过旧
