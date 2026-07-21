---
name: make-skill
description: 把当前会话沉淀为可复用的 skill。触发词：「把这个变成 skill」「记住这个流程」「保存为技能」
enabled: true
category: meta
---
# Make Skill — 会话沉淀为可复用技能

把当前对话中的工作流、排错路径、配置步骤沉淀为 skill，供后续复用。

## 两阶段流程

### Phase A：提出计划
1. 从对话中提炼核心流程（最多 5 个关键步骤）
2. 用自然语言向用户描述计划，等待确认
3. 不要让用户直接 approve 空白内容——先给计划

### Phase B：执行创建
用户确认后：
1. 使用 `plugin.create --type script --name "<技能名>"` 创建骨架
2. 使用 `fs.write` 将技能内容写入 `{DataPaths.SKILLS}/<技能名>.md`
3. 技能文件格式参考本文件的 YAML frontmatter + Markdown 正文
4. 使用 `skill.ls` 验证技能已创建

## Skill 文件格式

```markdown
---
name: <skill-name>
description: <一句话描述触发场景>
enabled: true
category: <general|dev|office|browser|system>
---

# <技能标题>

## 适用场景
- ...

## 执行步骤
1. ...
2. ...

## 注意事项
- ...
```

## 示例

用户说「记住我是怎么查天气的」→ Agent 提炼流程为 skill：

```markdown
---
name: check-weather
description: 通过 wttr.in 查询天气
enabled: true
category: general
---

# 查天气

## 步骤
1. net.curl "wttr.in/{city}?format=3"
2. 将结果翻译为中文展示给用户
```
