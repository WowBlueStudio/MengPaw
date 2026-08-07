---
name: plugin-index
description: 插件命令总索引。先读这个找到需要的插件，再按需查阅具体说明书。触发词：「插件总览」「有哪些插件」「插件索引」
enabled: true
category: system
source: core
---
# 插件命令索引

## 内置（始终可用）

| 命名空间 | 说明书 |
|---------|--------|
| `self` (16命令) — Agent自我管理 | `skill.run self` |
| `agent` (12命令) — 文档记忆 | `skill.run agent-system` |
| `plugin` (11命令) — 插件管理 | `skill.run plugin-system` |
| `sys` (39命令) — Android系统 | `skill.run android-system` |
| `framework` (11命令) — 框架通信 | `skill.run framework` |

## 功能插件

| 命令 | 说明书 |
|------|--------|
| `fs.*` — 文件系统 | `skill.run filesystem` |
| `net.*` — 网络请求 | `skill.run network` |
| `tavily.*` — AI搜索 | `skill.run tavily` |
| `translate.*` — 翻译 | `skill.run translate` |
| `hermes.*` — 多智能体 | `skill.run hermes` |
| `render.*` — 图像生成 | `skill.run render` |
| `update.*` — 自更新 | `skill.run self-update` |
| `browser.*` — 浏览器操控 (45命令) | `skill.run browser-control` |
| `browser.*` → Playwright 映射 | `skill.run browser-playwright` |
| `browser.*` → 爬虫工作流 | `skill.run browser-spider` |
| `browser.*` → 表单自动化 | `skill.run browser-form` |
| `browser.*` → 调试排障 | `skill.run browser-debug` |
| `browser.*` → 插件开发 API | `skill.run browser-tools` |
