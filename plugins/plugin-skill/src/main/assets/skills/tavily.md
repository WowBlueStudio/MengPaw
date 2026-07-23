---
name: tavily
description: Tavily AI搜索 — 结构化搜索+网页提取。触发词：「搜索」「查一下」「找资料」
enabled: true
category: general
---
# Tavily AI 搜索

专为AI Agent设计的搜索引擎。返回结构化结果而非HTML页面。

## 前置条件
需要 TAVILY_API_KEY（免费1000次/月）。在设置页或Vault中配置。

## 命令

### tavily.search — AI搜索
```
tavily.search <query> [--max=N]
```
- query: 搜索关键词（必填，中英文都支持）
- --max: 返回结果数（默认5）
- 返回: AI摘要 + 结构化结果(标题/URL/内容片段)

示例:
```
tavily.search 2024年AI发展趋势 --max=3
tavily.search Python asyncio best practices
```

### tavily.extract — 网页内容提取
```
tavily.extract <url>
```
提取网页原始文本（最多8000字符），用于深入阅读搜索结果页面。

## 使用策略
1. 先search找页面 → 再extract读内容
2. 中文问题用中文搜索词，结果更精准
3. 搜索结果可存memory: `agent.memory.record <摘要>`
4. 文档可保存到工作区: `fs.write /path/file.md <内容>`

## 故障排查
- 搜索失败 → 检查TAVILY_API_KEY: `self.config`
- 无结果 → 换更精确的搜索词，或用extract直接读URL
