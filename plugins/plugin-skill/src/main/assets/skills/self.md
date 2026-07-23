---
name: self
description: Agent自省命令参考 — 查看状态、配置、工具列表，向用户推送消息
enabled: true
category: system
---
# self — Agent自省 (13命令)

## 最常用
| 命令 | 说明 |
|------|------|
| `self.tools [namespace]` | **列出所有可用命令** — 每次新任务先跑这个 |
| `self.status` | Agent运行状态、会话信息 |
| `self.config [key=value]` | 查看/修改配置 |
| `self.version` | MengPaw版本号 |
| `self.time [iso|date|time|timestamp]` | 当前时间 |
| `self.stats` | 运行统计（步数/Token/缓存） |

## 推送通知
| 命令 | 说明 |
|------|------|
| `self.notify.message <text>` | 推送消息到聊天界面 |
| `self.notify.banner <text> [--level info|success|warn|error]` | 顶端横幅，4秒消失 |

## 其他
`self.avatar` `self.theme` `self.mcp` `self.trigger` `self.acp`

## 黄金法则
**新任务 → `self.tools` 看能做什么**
**长任务完成 → `self.notify.message` 通知用户**
