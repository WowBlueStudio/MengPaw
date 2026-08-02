---
name: browser-spider
description: 网页抓取工作流 — 唤醒、导航、提取、转档、分页、去重、持久化 (经 MCP + search.*)。触发词：「抓取这个网页」「采集这个列表」「把这页转成文档」「爬这个网站」
enabled: true
category: browser
---
# 网页抓取工作流

> 通道: `sys.browser.open` 唤醒 / `browser.mcp.invoke <工具>` 提取 / `search.md` 转档。主手册: `skill.run browser-control`。

## 适用场景

批量抓取网页内容、列表页逐条归档、需要登录/JS 渲染的页面采集。

## 执行步骤

```
# 1. 唤醒并打开
sys.browser.open https://example.com/list

# 2. 提取页面结构
browser.mcp.invoke browser_extract {}

# 3. 转档保存 (供后续阅读/提炼)
search.md https://example.com/list --name list_1

# 4. 列表页提取链接 → 逐条转档
browser.mcp.invoke browser_eval {"script":"JSON.stringify(Array.from(document.querySelectorAll('a')).map(a=>a.href).filter(h=>h.includes('/article/')))"}
→ 对每个 URL: search.md <url> --name article_N

# 5. 分页
browser.mcp.invoke browser_eval {"script":"var n=document.querySelector('.next');if(n){n.click();'next'}"}
```

## 抓取策略选择

| 场景 | 通道 | 原因 |
|------|------|------|
| 需登录/JS 渲染/反爬强的页面 | MCP (浏览器) | 真实浏览器环境, 带 cookie/JS |
| 静态页面/批量抓取 | `search.md` / `net.curl` | 快, 不占浏览器, 可并发 |
| 高质量搜索 | `tavily.search` | 结构化结果, 免解析 |

## MCP 工具清单（browser.mcp.invoke）

`browser_navigate` / `browser_extract` / `browser_eval` / `browser_click` / `browser_type` / `browser_screenshot`（6 个，`browser.mcp.tools` 查看详情）。抓取常用：navigate → extract → eval。

## 去重与持久化

- 转档产物在 `SEARCH_OUTPUTS` (`search.outputs` 查看)，文件名带时间戳
- 抓取前 `search.outputs` 查重，避免重复转档；`search.clean` 清理无用转档
- 关键资料提炼后 `agent.memory.keep` 沉淀

## 反爬应对

- 403/验证码 → 换浏览器通道 (MCP) 重试
- 限流 → 逐条间隔 + `search.md` 分步
- 需要登录 → 浏览器会话天然带登录态 (browser_navigate 登录后保持)

## 注意事项

- 大页面前 `browser_extract` 核对结构再批量 eval，避免选择器失配空转
- 转档命名用语义名（list_1/article_N），便于后续检索定位

## 进化目标

- 目标: 覆盖主流抓取场景——静态/JS/登录/分页/去重/持久化全链路
- 稳定锚点: 唤醒→提取→转档→去重的核心链路与 `search.md --name` 命名约定
- 收敛原则: 升级朝链路完整度收敛；新抓取场景开新技能，不污染本技能
