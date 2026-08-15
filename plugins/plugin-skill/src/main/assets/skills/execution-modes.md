---
name: execution-modes
description: 执行模式详解 — 6 种斜杠命令模式 (/Swarm /Fleet /Goal /Plan /Research /Silent)。触发词：「有什么模式」「执行模式」「斜杠命令」「模式菜单」
enabled: true
category: system
source: core
---
# 执行模式 — 斜杠命令菜单（6 种）

斜杠命令是 MengPaw 特有功能：用户在输入框点 **+** 进入执行模式区选择（没有 Normal/Deep/Dream 模式）。选择后消息带标签，自动切换执行策略，无需额外处理。

## /Swarm（火种模式）
Swarm 是进化版的 Mission：继承"拆解→并行 Worker→验证→合成"编排与降级通过语义，进化出角色混合模型、Andon 失败协议（重派/终止，不静默重试）与共享步数预算防失控。适合大规模检索/批量处理/多视角复合任务。Worker 不写记忆、不保留跨任务上下文。

## /Fleet（步坦协同模式）
装甲集群推进 + 步兵协同清剿：多 Agent 编队协同、跨设备分布式执行复杂任务（tribe.fleet 引擎）。

## /Goal
单目标驱动 → RubricGate 自动评估「目标完成了吗?」→ YES 结束 / NO 继续。

## /Plan
LLM 先分解 3-7 步计划 → 每步独立 mini ReAct 执行 → 逐步标记完成 → 汇总。

## /Research
多轮搜索（tavily/web）→ 交叉验证每条信息 → 来源标注 → 结构化综合报告。

## /Silent
后台静默执行，不阻塞对话，完成后以系统消息推送结果。

## 要点
- 消息带标签时自动切换执行策略，不要额外询问
- 用户问「有什么模式」：用本剧本列出全部 6 种，说明怎么在输入框 + 号里选
- 模式说明如有变化，更新本文件（不直接改系统提示词）
