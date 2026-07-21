---
name: loop-mode-reference
description: QwenPaw Goal/Mission 模式分析 — 参考实现 MengPaw 内置 Goal/Mission/Mission+
metadata:
  type: reference
---

# QwenPaw Loop Mode 参考分析

> 来源: https://github.com/agentscope-ai/QwenPaw (已 clone 到 /tmp/QwenPaw)

## Goal Mode 架构

### 核心组件
- **GoalSession**: goal/active/iteration/max_iterations/max_tokens/tokens_used/last_verdict/last_feedback
- **Gates** (按优先级排序):
  1. DoomLoopGate — 无限循环检测
  2. GoalTurnGate (p=10) — 迭代计数 + 上限检查 + 继续提示
  3. GoalBudgetGate (p=20) — token 预算检查
  4. RubricGate (p=30) — LLM 基于 Rubric 的完成评估 (关键创新!)
  5. CompletionGate — 自动完成检测
- **Tools**: get_goal / create_goal / update_goal
- **Prompt**: INITIAL_GOAL_PROMPT（首轮）/ CONTINUATION_PROMPT（后续轮）

### RubricGate 核心逻辑
```
SATISFIED → TERMINATE (goal completed)
otherwise → BYPASS (继续)
```
评估策略:
- `DefaultRubric` — 无 rubric，始终 SATISFIED
- `GoalStatusRubric` — 检查 session.active flag
- `QualitativeRubricGate` — 纯文本回复时防止过早停止

## Mission Mode 架构
- **PRD 式任务分解**: userStories[] 含 passes/status/worker/verify
- **Worker Agent 调度**: 分配 userStory 给 worker
- **进度追踪**: progress.txt 实时更新
- **Verifier**: 独立验证 agent 检查每步完成情况
- **Git 上下文检测**: 自动检测 git repo

## MengPaw 集成方案
1. Goal 模式 → AgentEngine.runWithGoal() — 带 RubricGate 自动完成评估
2. Mission 模式 → AgentEngine.runWithMission() — 任务分解 + Worker 调度
3. Mission+ 模式 → Mission + 跨 ACP 框架协调
4. 移除 plugin-agent-loop 和 plugin-agent-mission 独立插件
5. SettingsScreen 的 LoopMode 直接控制引擎内置模式

[[office-mcp-strategy]]
