---
name: plugin-index
description: 能力总索引 — 按领域找到对应说明书。触发词：「插件总览」「有哪些插件」「插件索引」「有什么能力」
enabled: true
category: system
source: core
---
# 能力索引

> 命令清单以 `self.tools [命名空间]` 为权威（实时注册表），本页只做**领域 → 说明书**导航。

## 内核命名空间（始终可用）

| 命名空间 | 用途 |
|---------|------|
| `self` | Agent 自我管理（状态/配置/发现/端口/通知） |
| `agent` | 文档与三轨记忆（memory/docs/session/output） |
| `plugin` | 插件管理（市场/安装/启停/校验） |
| `evolution` | 进化系统（审计/上报/反馈闭环） |
| `security` | 攻击来源黑名单 |
| `swarm` / `fleet` | 火种模式 / 舰队指挥 |

## 说明书导航（`skill.run <名称>`）

| 领域 | Skill |
|------|-------|
| 文件系统（Linux 命令直通） | `filesystem` |
| Android 系统与排查 | `android` |
| 设备操控（悬浮窗/日历/Root） | `device-control` |
| Termux / Python 环境 | `termux` |
| 插件系统 | `plugin-system` |
| 自更新 | `self-update` |
| 会话 | `sessions` |
| 记忆孪生 | `twin-guide` |
| 多智能体协作（`tribe.*`） | `hermes` |
| 技能创建与进化 | `make_skills` / `find_skills` |
| 执行模式 | `execution-modes` |
| 协议（ACP/MCP） | `protocols` |
| 进化分支 | `evolution` / `evolution-branch` |
| 浏览器操控 | `browser-control`（主手册） |
| 浏览器爬虫工作流 | `browser-spider` |
| 浏览器表单自动化 | `browser-form` |
| 浏览器排障 | `browser-debug` |
| Playwright 对照 | `browser-playwright` |
| Tavily AI 搜索 | `tavily` |

## 外置插件（需安装）

| 插件 | 命名空间 | 安装 |
|------|---------|------|
| 网页转档（正文提取→Markdown） | `search.*` | `plugin.install browser-search-plugin` |
| 图像生成 | `render.*` | `plugin.install render-plugin` |
| 翻译 | `translate.*` | `plugin.install translate-plugin` |
| 其他 | — | `plugin.marketplace` 浏览全部 |
