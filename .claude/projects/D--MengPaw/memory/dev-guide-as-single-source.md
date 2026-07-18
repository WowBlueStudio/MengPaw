---
name: dev-guide-as-single-source
description: MengPaw-Development-Guide.md 是项目唯一权威知识中心，所有开发工作必须同步更新该文档
metadata:
  type: feedback
---

MengPaw-Development-Guide.md 是项目的单一事实来源 (single source of truth)。每次代码变更（新增功能、修改架构、修复 bug、更新依赖、调整 CLI 命令等）都必须同步更新该文档中对应的章节。

**Why:** 该文档是项目交接和新人上手的第一入口，1000+ 行覆盖了架构、CLI 规范、安全模型、API 参考、构建部署、常见问题等全部内容。代码与文档不一致会导致接手者困惑。

**How to apply:**
1. 每次开发任务开始前，先读 MengPaw-Development-Guide.md 了解现状
2. 代码变更完成后，立即更新文档对应章节
3. 更新文档顶部的「更新日期」和「版本历史」表格
4. 如果修改了 CLI 命令，同步更新第 5 节 CLI 规范
5. 如果修改了模块结构，同步更新第 4 节模块体系和目录结构
6. 如果增加了测试，同步更新测试结果统计
7. 如果解决了技术债务，同步更新第 12.5 节已知问题
