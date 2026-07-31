# 文档索引

> 按场景选择加载，避免一次性塞入全部上下文。

---

## 架构参考（大量，按需加载）

| 文件 | 大小 | 内容 | 何时读 |
|------|:----:|------|--------|
| `memory-twin-architecture.md` | 47 KB | 记忆孪生完整设计：数字孪生范式、五层架构、数据模型、同步协议、配对安全、能力路由、P0-P3 全部修复 | 修改孪生插件时 |
| `MengPaw-Development-Guide.md` | 39 KB | 项目单一事实来源：架构总览、模块清单、CLI 参考、安全模型、插件开发 | 每次开发任务开始前 |
| `code-review-9-dimensions.md` | 9 KB | v0.17.1 九维审查总结：可维护性/可读性/健壮性等 102 项发现与修复 | 做代码审查时参考 |
| `tribe-vs-hermes-comparison.md` | 9 KB | Hermes vs Tribe 多智能体协作机制对比，十特性已全量实现 | 修改 tribe 插件时 |
| `crash-prevention-guide.md` | 10 KB | 30+ 闪退案例：进程保活/空安全/文件 IO/生命周期/Compose 陷阱 | 遇到闪退或做稳定性修复时 |
| `lessons.md` | 9 KB | v0.16.0 开发经验：缓存设计、I/O 窗口、启动优化、模式重构、BM25 | 做架构决策或性能优化时 |

## 参考速查（小体积，可常驻）

| 文件 | 大小 | 内容 |
|------|:----:|------|
| `compilation-issues.md` | 1 KB | 10 类编译错误速查表 |
| `audit-methodology.md` | 4 KB | 三层十二问审计方法论 |
| `audit-records.md` | 3 KB | 各版本审校记录清单 |
| `roadmap.md` | 2 KB | 开发路线图 Phase 1-10 |

## 根目录文档

| 文件 | 大小 | 内容 | 何时读 |
|------|:----:|------|--------|
| `README.md` | 8 KB | 项目介绍、快速开始、架构、核心概念 | 新开发者入门 |
| `CONTRIBUTING.md` | 3 KB | 环境搭建、代码规范、构建命令 | 准备开发环境或提交 PR 时 |
| `RELEASE.md` | 4 KB | 版本发布流程 | 做 release 时 |

## 法律文件

| 文件 | 大小 | 内容 |
|------|:----:|------|
| `legal/COPYRIGHT-CERTIFICATE.md` | 4 KB | 版权声明 (中文) |
| `legal/COPYRIGHT-CERTIFICATE-EN.md` | 4 KB | 版权声明 (英文) |

---

> **用法**：每会话先加载本文档（~3KB），根据当前任务从左侧「何时读」列判断需要加载哪些文件，再用 Read 按需读取。
