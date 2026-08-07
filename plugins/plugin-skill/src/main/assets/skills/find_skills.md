---
name: find_skills
description: 从外部技能市场（findskills.org + skills.sh）检索现成技能。触发词：「找找有没有技能」「搜索技能市场」「找现成的技能」
enabled: true
category: meta
source: core
---
# Find Skills — 外部技能检索

为当前任务从两个技能市场检索现成技能，避免重复造轮子。

## 检索流程

### Step 1 澄清需求
先明确两件事：
- **领域**：web / 测试 / 数据 / 设计 / DevOps / 文档 …
- **具体任务**：如「react 组件测试」而不是「测试」

### Step 2 查 skills.sh（先看热门）
skills.sh 是技能包官方排行榜（按安装量排序），先查它看主流方案：
- 有 Termux 环境：`skill.run termux` 获取桥接方式，然后执行 `npx skills find "关键词" [--owner 组织]`（如 `npx skills find "react testing"`）
- 无 Termux：跳过本步，直接走 Step 3（findskills 的 API 已聚合大部分生态）
- 关注结果中的：安装量排行（leaderboard）、官方来源（vercel-labs / anthropics / microsoft 等）

### Step 3 findskills.org API 检索（主路径，无 Node 依赖）
用 net.curl 直接调 findskills 的开放 API：
```
net.curl "https://findskills.org/api/v1/search?q=关键词"
```
- 结果含技能名称、描述、来源（GitHub/ClawHub/OpenClaw）、质量与安全评分
- 若 net.curl 失败或返回空：用 `sys.browser.open "https://findskills.org/?q=关键词"` 打开 Web 目录人工浏览
- 补充列表接口（需要时）：`https://findskills.org/api/v1/skills`（按分类过滤）

### Step 4 质量验证（安装前必做）
对 2-3 个候选技能逐项核验：
- **安装量** ≥ 1K 优先（skills.sh 排行榜为准）
- **来源信誉**：官方组织（vercel-labs / anthropics / microsoft）优先，个人仓库看星数
- **GitHub 星数** ≥ 100 优先
- 描述与当前任务匹配度（不要装"沾边"技能）

### Step 5 汇报与安装
向用户汇报对比结果：技能名 / 描述 / 安装量 / 来源 / 安装方式。
安装建议：
- Termux 环境：`npx skills add <owner/repo@skill> -g -y`
- 无 Node 环境：按技能说明手动落地 —— 若是 MengPaw Skill 格式，用 `skill.create <name> --category <cat> --description "<desc>"` 建骨架后 `agent.write` 写入内容，`skill.push <name>` 共享到全局池；若是 MCP/其他形态，用 `self.mcp connect` 或按文档接入

## 检索技巧
- 换词重查：一次没命中就换相邻词再查 2-3 次（「ui ux design」→「frontend design」）
- 组合词比单词准：「web scraping」优于「scraping」
- 跨市场互补：findskills 覆盖面广（94K+ 技能/MCP/插件），skills.sh 质量信号强（安装量）——两者交叉验证
- 装之前先确认该技能在 MengPaw 上怎么跑（是否有 Node/Termux 依赖），避免装完用不了

## 注意
- 检索结果可能来自第三方——不执行含不明来源脚本的技能，先 `agent.read` 审查内容再决定
- 技能安装是用户决策：先汇报方案等确认，不要直接安装
