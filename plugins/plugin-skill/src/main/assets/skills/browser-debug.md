---
name: browser-debug
description: 浏览器协作排障 — MCP 工具常见错误、桥离线、页面提取失败的处理
enabled: true
category: browser
---
# 浏览器协作排障

> 通道: `sys.browser.open` 唤醒 / `browser.mcp.*` 工具 / `search.*` 转档。主手册: `skill.run browser-control`。

## 常见错误速查

| 错误 | 原因 | 处理 |
|------|------|------|
| `browser.mcp.status` 离线 | 浏览器未运行 / 被杀 | `sys.browser.open` 唤起 |
| `{"ok":false,"error":"WebView not available"}` | 无打开标签页 | `sys.browser.open <url>` 先开页 |
| `Missing 'selector'` | 参数缺失 | `browser.mcp.invoke browser_click {"selector":"#btn"}` |
| `Selector not found: ...` | 选择器错误 / 元素未加载 / 在 iframe 内 | 检查选择器; `browser_eval {"script":"document.querySelectorAll('button').length"}` 探测; 先等加载 |
| 页面提取为空 | 页面 JS 渲染未完成 | 先 `browser_navigate` 等加载 (最坏 10s) 再 `browser_extract` |
| `search.md` 抓取失败 | 网络 / 反爬 / URL 非法 | `net.curl <url>` 试抓; 确认 http/https; 用 `search.clean` 分步 |

## 诊断流程

```
1. browser.mcp.status          # 桥在线?
2. sys.browser.open <url>      # 不在线则唤起
3. browser.mcp.invoke browser_eval {"script":"document.title"}   # 页面可达?
4. browser.mcp.invoke browser_extract {}                          # 内容可提?
```

## 注意事项

- 桥仅监听 127.0.0.1 (设备内), 不暴露局域网
- `browser_navigate` 阻塞等待加载 (最坏 10s), 大页面耐心等待
- 浏览器进程被杀后桥自动停止, 重新唤起即恢复
