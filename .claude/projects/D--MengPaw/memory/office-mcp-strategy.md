---
name: office-mcp-strategy
description: 办公文档能力规划 — MP MarkDown 替代 Word/PowerPoint/Excel
metadata:
  type: project
---

# 办公文档能力规划

> 不在 MengPaw 本体实现，留待独立 MCP 服务项目。

## 策略

| 传统办公 | 替代方案 | 技术选型 |
|---------|---------|---------|
| **Word** (.docx) | MarkDown 实时同步编辑 | MCP Server 暴露 tools，Agent 和用户可同步编辑 .md 文件 |
| **PowerPoint** (.pptx) | HTML + Reveal.js 幻灯片 | Agent 生成 HTML 页面，Reveal.js 渲染为演示文稿 |
| **Excel** (.xlsx) | 数据库表 | SQLite/Room 存储表格数据，SQL 查询代替公式 |

## 架构

```
MengPaw Agent ←→ MCP Protocol ←→ MP MarkDown Service
                                      ├── MarkDown Editor (WebSocket sync)
                                      ├── HTML+Reveal.js Renderer
                                      └── SQLite/Room Table Engine
```

## 关联记忆

- 参考 QwenPaw 的 docx/pptx/xlsx skills，但用更轻量的 Web 方案
- MCP 协议已在 kernel 中实现 (McpServer + McpClient)
- 当前 plugins/plugin-dev 已有 plugin.create 骨架生成能力
