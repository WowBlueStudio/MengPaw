---
name: guidance
description: 用户询问安装、配置、或「怎么用」「报错了」时触发；帮助定位文档和排查问题
enabled: true
category: system
---
# MengPaw 使用引导

当用户询问 **安装、配置、功能使用、报错排查** 时使用本 Skill。

## 核心原则

- **先查本地，再回答**：用 `agent.memory` 查阅已索引的文档
- **不臆测**：回答基于已读到的内容
- **中文优先**：回答语言与用户提问一致

## 标准流程

### 1. 确定问题类型

| 关键词 | 问题类型 | 查阅内容 |
|--------|---------|---------|
| 安装、下载、APK | 安装问题 | `agent.memory search 安装` → 检查 README |
| API Key、供应商、模型 | 配置问题 | `agent.memory search API` → 检查设置文档 |
| 插件、plugin、命令 | 功能问题 | `self.tools` → `plugin.list` |
| 报错、闪退、不工作 | 故障排查 | `agent.memory search 故障` → `agent.audit` |
| 权限、无法访问 | 权限问题 | `agent.memory search 权限` |

### 2. 查阅文档

使用 `agent.memory [query]` 搜索已有记忆和文档：
- CLI 参考：`agent.memory read cli-reference`
- 工具索引：`agent.memory read tool-index`
- 开发指南：`agent.memory search <关键词>`

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
