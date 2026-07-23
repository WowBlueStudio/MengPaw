---
name: hermes
description: 多智能体协作 — 发现/委派/团队共享记忆
enabled: true
category: general
---
# hermes — 多智能体协作 (6命令)

| 命令 | 说明 |
|------|------|
| `hermes.discover` | 发现可用Agent |
| `hermes.team` | 查看团队成员 |
| `hermes.delegate <agent> <task>` | 委派任务 |
| `hermes.ask <agent> <question>` | 提问 |
| `hermes.memo <content>` | 写团队共享记忆 |
| `hermes.role <agent> <role>` | 设置角色 |

## 协作流程
```
hermes.discover                      → 找Agent
hermes.delegate Agent-2 研究课题X     → 委派
hermes.memo 关键发现: ...             → 共享
hermes.team                          → 查看进度
```
