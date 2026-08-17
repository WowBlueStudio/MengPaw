# 文档索引

> 按场景选择加载，避免一次性塞入全部上下文。

---

## 架构参考（大量，按需加载）

| 文件 | 大小 | 内容 | 何时读 |
|------|:----:|------|--------|
| `lessons.md` | 38 KB | 经验教训库：§1-14 主题经验（v0.16~v0.29.2，含 Reasonix 对照/网络门卫 SPI）+ §15 历史教训浓缩（v0.2.2~v0.23.0 原 118 条要点化 + v0.34.1 NSD 事故） | 做架构决策、写插件、碰编译坑或性能优化时 |
| `crash-prevention-guide.md` | 10 KB | 30+ 闪退案例：进程保活/空安全/文件 IO/生命周期/Compose 陷阱 | 遇到闪退或做稳定性修复时 |
| `PROTOCOL.md` | 7 KB | 框架通信协议：双轨架构(本机 MCP 9881 / 远程 ACP 9876)、连接器 SPI 开发指南、消息格式、接入清单 | 对接外部框架、写连接器插件时 |
| `swarm-design.md` | 7 KB | 火种 (Swarm) 模式设计：规划器/Worker/Verifier/合成器、JIT 三闸门、Andon 协议 | 改火种模式或写多 Agent 任务时 |
| `browser-autopilot-plan.md` | 9 KB | MP 浏览器「半自动武器」升级方案：Playwright 语义命令面 (page.*)、Termux 式 am 桥调用、page.load 分段截图+按段坐标系统、browser.* 去重清单（2026-08-11 拍板，Phase 1-3 已实施；真机自测后退役 9880 桥） | 升级浏览器能力、做浏览器自动化或执行去重时 |
| `llm-multistage-dataflow.md` | 8 KB | LLM 多阶段输出数据流：ReAct 每回合 LLM/框架/UI 各环节实际收到的内容（parse 规则/Observation 组装/历史累积/边界防御） | 理解 Agent 循环、排查工具调用链路、调试 LLM 输出时 |
| `add-llm-provider.md` | 10 KB | 新增 LLM 供应商接入指南：官方文档原文核对表（9 家, 含核对日期）+ 当前支持厂商/模型名单登记表（10 预置, 与 SettingsModels.kt 同步铁律）+ 6 个代码改动点 + 官方格式测试（v0.41.0 基线） | 新增/审计 LLM 供应商、核对思考字段、查当前支持名单时 |
| `audit-methodology.md` | 7 KB | **三层十二问 · 功能闭环审计**：Agent 认知层(6问) + 软件逻辑层(6问) + 服务基础设施层(6问)，逐条过"Agent 能否自主完成功能闭环"（v0.24.0 清理后恢复 + 通用化抽象） | 审查新功能/子系统/插件是否闭环，或复盘"代码存在但 Agent 无法触达"类缺陷时 |
| `code-review-9-dimensions.md` | 8 KB | **九维代码审查法**：机器门禁先行 + 可维护性/可读性/可扩展性/灵活性/简洁性/可复用性/可测试性/健壮性/兼容性 九维逐维过（含搜索模式），输出 P0-P2 分级（v0.24.0 清理后恢复 + 通用化抽象） | 做 PR 评审、大文件重构、模块交接、技术债盘点时 |

## 参考速查（小体积，可常驻）

| 文件 | 大小 | 内容 |
|------|:----:|------|
| `pyramid-investigation.md` | 10 KB | 金字塔彻查法：从现象逐层拆链路、证据排除、反模式与实战案例（含证据等级 0.2.0） | 功能"永远修不好"或做链路级排障时 |
| `roadmap.md` | 2 KB | 开发路线图 Phase 1-10（Phase 1-6 完成，7-10 未来规划） |

> 已归档（git 历史可溯）：九维审查总结→Codex skill `mengpaw-pr-review`；审计方法论→记忆 `bug-audit-methodology`；编译问题速查→`lessons.md` §15；流式调查记录→主文档 §4.1.1 定论；审校记录→`CHANGELOG.md`；make-skill 对比→`CHANGELOG.md` v0.26.2；根发布流程 RELEASE.md→Codex skill `mengpaw-release`（2026-08-07 由 `.claude/skills/release.md` 迁移）。

## 根目录文档

| 文件 | 大小 | 内容 | 何时读 |
|------|:----:|------|--------|
| `MengPaw-Development-Guide.md` | 118 KB | 项目单一事实来源：架构总览、模块清单、CLI 参考、安全模型、插件开发、技能双层模型/来源标记、设置页面板设计（v0.34.0）（**在仓库根目录**，非 docs/） | 每次开发任务开始前 |
| `README.md` | 8 KB | 项目介绍、快速开始、架构、核心概念 | 新开发者入门 |
| `CONTRIBUTING.md` | 3 KB | 贡献指南：反馈渠道、版权让渡、PR 评审流程 | 准备提交 Issue/PR 时 |

## 法律文件

| 文件 | 大小 | 内容 |
|------|:----:|------|
| `LICENSE` | 22 KB | 社区版许可全文 (AGPL-3.0, 未经删改) |
| `COMMERCIAL-LICENSE.md` | 4 KB | 双许可总声明 + 商业授权条款草案 |
| `legal/COPYRIGHT-CERTIFICATE.md` | 4 KB | 版权声明 (中文) |
| `legal/COPYRIGHT-CERTIFICATE-EN.md` | 4 KB | 版权声明 (英文) |

---

> **用法**：每会话先加载本文档（~3KB），根据当前任务从左侧「何时读」列判断需要加载哪些文件，再用 Read 按需读取。
