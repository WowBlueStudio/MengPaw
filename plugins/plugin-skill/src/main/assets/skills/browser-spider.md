---
name: browser-spider
description: 网页抓取工作流 — 唤醒、导航、提取、转档、分页、去重、持久化 (经 MCP + search.*)
enabled: true
category: browser
---
# 网页抓取工作流

> 通道: `sys.browser.open` 唤醒 / `browser.mcp.*` 提取 / `search.md` 转档。主手册: `skill.run browser-control`。

## 完整流程

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

## 去重与持久化

- 转档产物在 `SEARCH_OUTPUTS` (`search.outputs` 查看), 文件名带时间戳
- 抓取前 `search.outputs` 查重, 避免重复转档
- 关键资料提炼后 `agent.memory.keep` 沉淀

## 反爬应对

- 403/验证码 → 换浏览器通道 (MCP) 重试
- 限流 → 逐条间隔 + `search.md` 分步
- 需要登录 → 浏览器会话天然带登录态 (browser_navigate 登录后保持)
