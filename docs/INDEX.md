# 文档索引

> 按场景选择加载，避免一次性塞入全部上下文。

---

## 架构参考（大量，按需加载）

| 文件 | 大小 | 内容 | 何时读 |
|------|:----:|------|--------|
| `lessons.md` | 26 KB | 经验教训库：§1-14 主题经验（v0.16~v0.29.2，含 Reasonix 对照/网络门卫 SPI）+ §15 历史教训浓缩（v0.2.2~v0.23.0 原 118 条要点化） | 做架构决策、写插件、碰编译坑或性能优化时 |
| `crash-prevention-guide.md` | 10 KB | 30+ 闪退案例：进程保活/空安全/文件 IO/生命周期/Compose 陷阱 | 遇到闪退或做稳定性修复时 |
| `PROTOCOL.md` | 7 KB | 框架通信协议：双轨架构(本机 MCP 9881 / 远程 ACP 9876)、连接器 SPI 开发指南、消息格式、接入清单 | 对接外部框架、写连接器插件时 |
| `swarm-design.md` | 7 KB | 火种 (Swarm) 模式设计：规划器/Worker/Verifier/合成器、JIT 三闸门、Andon 协议 | 改火种模式或写多 Agent 任务时 |

## 参考速查（小体积，可常驻）

| 文件 | 大小 | 内容 |
|------|:----:|------|
| `pyramid-investigation.md` | 6 KB | 金字塔彻查法：从现象逐层拆链路、证据排除、反模式与实战案例 | 功能"永远修不好"或做链路级排障时 |
| `roadmap.md` | 2 KB | 开发路线图 Phase 1-10（Phase 1-6 完成，7-10 未来规划） |

> 已归档（git 历史可溯）：九维审查总结→`.claude/skills/pr-review.md`；审计方法论→记忆 `bug-audit-methodology`；编译问题速查→`lessons.md` §15；流式调查记录→主文档 §4.1.1 定论；审校记录→`CHANGELOG.md`；make-skill 对比→`CHANGELOG.md` v0.26.2。

## 根目录文档

| 文件 | 大小 | 内容 | 何时读 |
|------|:----:|------|--------|
| `MengPaw-Development-Guide.md` | 44 KB | 项目单一事实来源：架构总览、模块清单、CLI 参考、安全模型、插件开发（**在仓库根目录**，非 docs/） | 每次开发任务开始前 |
| `README.md` | 8 KB | 项目介绍、快速开始、架构、核心概念 | 新开发者入门 |
| `CONTRIBUTING.md` | 3 KB | 贡献指南：反馈渠道、版权让渡、PR 评审流程 | 准备提交 Issue/PR 时 |
| `RELEASE.md` | 4 KB | 版本发布流程 | 做 release 时 |

## 法律文件

| 文件 | 大小 | 内容 |
|------|:----:|------|
| `LICENSE` | 22 KB | 社区版许可全文 (AGPL-3.0, 未经删改) |
| `COMMERCIAL-LICENSE.md` | 4 KB | 双许可总声明 + 商业授权条款草案 |
| `legal/COPYRIGHT-CERTIFICATE.md` | 4 KB | 版权声明 (中文) |
| `legal/COPYRIGHT-CERTIFICATE-EN.md` | 4 KB | 版权声明 (英文) |

---

> **用法**：每会话先加载本文档（~3KB），根据当前任务从左侧「何时读」列判断需要加载哪些文件，再用 Read 按需读取。
