---
name: browser-debug
description: 浏览器协作排障 — page.* / am 桥单通道常见错误、存储权限、页面提取失败的处理。触发词：「浏览器报错」「桥离线」「页面提取失败」「截图失败」
enabled: true
category: browser
source: core
---
# 浏览器协作排障

> 通道: `sys.browser.open` 唤醒 / am 桥单通道 (page.*/browser.*) / `search.*` 转档。
> 9880 HTTP 桥与 MCP 工具已退役 (v0.9.0)。主手册: `skill.run browser-control`。

## 常见错误速查

| 错误 | 原因 | 处理 |
|------|------|------|
| am 桥返回"浏览器未就绪" | 浏览器进程未运行 | `sys.browser.open <url>` 先唤醒再调 |
| `page.load` 提示存储权限未授予 | 未授权「所有文件访问」 | 浏览器首启弹窗引导, 或系统设置 → 应用 → MP 浏览器 → 所有文件访问; 拒绝后每次 page.load 提示重授 |
| `page.click` 错位/超界 | 坐标不是最近截图坐标 | 先 `page.screenshot --full` 刷新段图, 用返回的段号 + 段内坐标 |
| 分段截图 `partial:true` | 页面超长截断 (30 段上限) | 属正常行为, 按已返回段操作; 需要更下部内容可 `page.scroll_by` 后重截 |
| `{"ok":false,"error":"WebView not available"}` | 无打开标签页 | `sys.browser.open <url>` 先开页 |
| `Missing 'selector'` | 参数缺失 | 检查 page.* 命令参数 (如 `page.click <css>`) |
| `Selector not found: ...` | 选择器错误 / 元素未加载 / 在 iframe 内 | 检查选择器; `page.eval "document.querySelectorAll('button').length"` 探测; 先等加载 |
| 页面提取为空 | 页面 JS 渲染未完成 | 先 `page.goto` 等加载 (最坏 10s) 再 `page.content` |
| `search.md` 抓取失败 | 网络 / 反爬 / URL 非法 | `net.curl <url>` 试抓; 确认 http/https; 用 `search.clean` 分步 |

## 诊断流程

```
1. sys.browser.open <url>      # 不在线则唤起
2. am 桥 page.title            # 页面可达? (page.* 命令串整体引号包裹)
3. am 桥 page.content --head 20   # 内容可提?
4. am 桥 page.screenshot --view   # 截图可落盘? (权限问题见上表)
```

## 注意事项

- am 桥 payload 必须引号包裹整体 (`"-c,page.goto https://..."`), 参数含空格同理
- am 桥 signature 权限 — 仅同签名 Shell 可调, 第三方 App 拒绝
- 9880 桥与开放模式已退役 (v0.9.0), 不再有 `browser.mcp.*` 工具 / `/mcp` `/health` 端点
- 浏览器进程被杀后通道自动停止, 重新唤起即恢复
