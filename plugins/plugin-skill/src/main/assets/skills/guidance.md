---
name: guidance
description: 用户询问安装、配置、或「怎么用」「报错了」时触发；帮助定位文档和排查问题。触发词：「怎么用」「怎么装」「报错了」「这个功能在哪」
enabled: true
category: system
---
# MengPaw 使用引导

## 适用场景

用户询问 **安装、配置、功能使用、报错排查** 时使用本 Skill。

## 核心原则

- **先查本地，再回答**：用 `agent.memory.search <关键词>` / `agent.docs` 查阅已索引内容
- **不臆测**：回答基于已读到的内容
- **语言跟随用户**：回答语言与用户提问一致

## 执行步骤

### 1. 确定问题类型

| 关键词 | 问题类型 | 查阅内容 |
|--------|---------|---------|
| 安装、下载、APK | 安装问题 | `agent.memory.search 安装` → README / RELEASE.md |
| API Key、供应商、模型 | 配置问题 | `agent.memory.search API` → 设置文档 |
| 插件、plugin、命令 | 功能问题 | `self.tools` → `plugin.list` / `plugin.search` |
| 报错、闪退、不工作 | 故障排查 | `agent.memory.search 故障` → `agent.audit` / `evolution.audit` |
| 权限、无法访问 | 权限问题 | `agent.memory.search 权限` |
| 设备间通信、框架发现 | 框架通信 | `framework.discover` / `framework.peers` / `framework.info` |

### 2. 查阅文档

- 文档清单：`agent.docs`（Soul/Agents/Memory/Boost/Profile）
- 记忆检索：`agent.memory.search <关键词>`（点号分隔，非空格）
- CLI 参考：`agent.cli`
- 失败记录：`evolution.audit`（技能失败模式）→ 修后 `evolution.mark-corrected`

### 3. 如果本地无答案

```
我查阅了本地文档，关于「{问题}」暂未找到直接答案。

建议：
1. 检查 README.md 是否已生成到 Agent 文档中
2. 使用 plugin.marketplace 查看是否有相关插件
3. 访问 https://github.com/WowBlueStudio/MengPaw 查看最新文档
```

### 4. 回答模板

```
## {问题简述}

**原因**：{根因分析}

**解决步骤**：
1. {步骤一}
2. {步骤二}

**验证**：{如何确认已修复}
```

## 注意事项

- 记忆检索命令是 `agent.memory.search`（点号），不是 `agent.memory search`（空格）
- 故障排查优先 `agent.audit`（会话审计）+ `evolution.audit`（技能失败记录）双查
- 修完问题后可用 `agent.memory.keep` 沉淀本次排障路径，避免重复排查

## 进化目标

- 目标: 覆盖 MengPaw 全部功能域的引导与排障路径
- 稳定锚点: 「先查本地再回答」原则与问题类型→查阅内容映射表
- 收敛原则: 升级朝引导覆盖度收敛；新功能域排障开新技能或并入映射表
