---
summary: "CRON 定时任务规则 — 精确到点、必须执行的任务"
read_when:
  - 触发器任务触发（[触发器任务 · CRON]）
  - self.trigger
---

# heartbeat.md — CRON 定时任务规则

此文件指导你在 **CRON 触发器**命中时应该做什么。**留空或只有注释 = 跳过所有定时任务。**

随机对话（真人感）的规则不在这里——那是 `trueman.md` 的职责。

---

## CRON 触发器（精确定时）

```
self.trigger add cron <id> <cron-expr> <action描述>

# 每天早上 9:00 生成昨日摘要
self.trigger add cron morning-report 0 9 * * * 生成昨日摘要发送给用户

# 每 30 分钟检查一次工作区
self.trigger add cron health-check */30 * * * * 工作区状态检查
```

CRON 使用 ±5 分钟模糊窗口，不需精确到秒。

---

## 你该怎么做

1. 检查此文件（heartbeat.md）中是否有对应 `id` 的任务说明
2. 如果没有，用 `self.trigger list` 查看触发的 action 描述
3. 静默执行任务，不要在聊天中输出冗长的思考过程
4. 结果写入中期记忆（`agent.memory.record`）或汇报给用户
5. 用 `notify.banner` 推送一句话结果（`--level warn` 表示需要用户关注的事项）

---

## 自定义规则

在此文件中添加你的定时任务规则。每行一个任务：

```
@cron <id> <cron表达式>
<任务说明>
```

示例：

```
@cron health-check */30 * * * *
检查设备存储空间和电池状态，异常时 notify.banner

@cron morning-report 0 9 * * *
用 agent.memory.mid 读昨天的中期记忆 → 总结要点 → agent.memory.keep 沉淀有价值的
```

---

## 要点

- **此文件留空 = 跳过所有定时任务**
- 用 `self.trigger` 查看和管理所有触发器
- 定时任务是后台执行的，不要阻塞用户当前对话
- 临时信息走 `agent.memory.record`（中期），沉淀知识才用 `agent.memory.keep`（长期）
- 随机闲聊的事交给 `trueman.md`
