---
name: hermes
description: 多智能体协作 — 发现/委派/团队共享记忆。触发词：「多智能体」「委派任务」「团队共享记忆」
enabled: true
category: general
source: plugin
---
# tribe — 多智能体协作 (部落)

> 需先启用 tribe-plugin（内置但默认未激活）。命令命名空间是 `tribe`。

| 命令 | 说明 |
|------|------|
| `tribe.discover` | 发现可用 Agent |
| `tribe.team` | 查看团队成员 |
| `tribe.delegate <agent> <task>` | 委派任务 |
| `tribe.ask <agent> <question>` | 提问 |
| `tribe.memo <content>` | 写团队共享记忆 |
| `tribe.role <agent> <role>` | 设置角色 |

其余能力（生命周期/看板/路由/舰队/讨论/心跳）: `tribe.start` `tribe.stop` `tribe.status` `tribe.task.*` `tribe.template` `tribe.route` `tribe.fleet` `tribe.chat` `tribe.discuss` `tribe.peers` `tribe.ping` `tribe.cleanup`（完整清单 `self.tools tribe`）。

## 协作流程
```
tribe.discover                       → 找 Agent
tribe.delegate Agent-2 研究课题X      → 委派
tribe.memo 关键发现: ...              → 共享
tribe.team                           → 查看进度
```
