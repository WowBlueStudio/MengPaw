---
summary: "定时任务行为规范 — 触发器命中时怎么执行"
read_when:
  - 手动引导工作区
---

_当 CRON 或随机对话（SCHEDULE）触发器命中时，你会收到一条以 `[触发器任务 · CRON]` 或 `[触发器任务 · SCHEDULE]` 开头的用户消息。_

## 默认行为

收到触发器任务后：

1. **静默执行** — 不要在聊天中输出冗长的思考过程。只做必要的事。
2. **读取相关文件** — 如果任务涉及"生成摘要"或"检查状态"，先读取 memory 目录（`agent.memory.mid` 看中期记忆，`agent.memory` 看长期记忆）和相关工作区文件。
3. **完成推送** — 执行完毕后，使用 `notify.banner` 推送一条简要结果：
   ```
   notify.banner <一句话结果> --level info
   ```
4. **异常告警** — 如果发现需要用户关注的事项（错误、风险、待处理），使用 `--level warn`：
   ```
   notify.banner <警告内容> --level warn
   ```

## 示例

| 触发器 | 执行 | 横幅 |
|--------|------|------|
| 每天 9:00 生成昨日摘要 | `agent.memory.mid 昨日日期` → 总结昨日工作 → `agent.memory.keep` 沉淀要点 | `notify.banner 昨日摘要已生成: 3 条记录, 1 项待跟进 --level info` |
| 每小时检查系统状态 | `sys.battery` + `sys.storage` + `sys.memory` | `notify.banner 系统正常: 电量82% 存储45GB可用 --level info` |
| 发现插件更新 | `plugin.update --all` → 有更新时推送 | `notify.banner 发现 2 个插件更新 --level warn` |

## 自定义

**你可以修改这个文件来改变触发器行为。** 例如：

- **关闭横幅推送** — 删除上面的 "完成推送" 步骤，Agent 将只在聊天中输出结果。
- **改为聊天通知** — 不用 `notify.banner`，改用 `notify.message` 将结果注入聊天。
- **添加前置检查** — 在任务前检查电量、网络等条件。
- **多步骤任务** — 将多个触发器动作串联成工作流。

## 注意事项

- 触发器在固定的 "MengPaw" 智能体会话中执行，不会创建新会话。
- 如果 Agent 正忙，触发器任务排队到 inbox 等待。
- CRON 使用 ±5 分钟模糊窗口，随机对话每天按设定次数出现。
- 用户点击通知横幅后会自动跳转到本会话。
- 定时任务的临时产出用 `agent.memory.record` 记到中期记忆（按日分片，不注入提示词），别用 `agent.memory.keep` 污染长期记忆。
