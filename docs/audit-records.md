# MengPaw 审校记录

> 各版本发布前的代码审查、功能审计、安全审查记录。
> 审计方法论详见 [audit-methodology.md](audit-methodology.md)。

| 日期 | 审校项 | 结果 |
|------|--------|------|
| 2026-07-26 | v0.15.2 功能闭环审计 | PromptEngine 三层十二问审计 → 6 问题全修: 缓存失效(路径前缀) + 缓存key(检查前置+docCache非空守卫) + Plan进度(中英双语边界标记) + 错误消息(LlmApiException替双重bodyAsText) + 容器高度(Step编号恢复+观察缺失修复) + 提示词(恢复插件发现few-shot) + MCP插件BrowserBridge解耦(toolExecutor委托) + RemoteApi超时120s。编译通过, 测试通过。 |
| 2026-07-25 | v0.15.0 记忆孪生全链路审计 | 三层十二问审计 → 14 问题全修: P0×6 (系统提示词/配对指引/ACP就绪/syncWithPeer/mDNS单点/命令命名空间), P1×6 (QoS/心跳/解绑UI/错误诊断/同步反馈/self.tools覆盖), P2×2 (协议版本/原子写入)。8 文件修改, 626 行新增, 编译通过, 测试通过。 |
| 2026-07-21 | v0.6.0 设计系统合规 | 11 个 UI 文件硬编码色值清零, 全部替换为 ArcoColors token |
| 2026-07-21 | v0.6.0 编译验证 | clean build 4m10s 通过, 15 文件修改, 编译问题 10 项已记录 |
| 2026-07-21 | 微内核拆分验证 | kernel (44文件) + core (6文件) 编译通过, 25插件编译通过, 83/88 测试通过 |
| 2026-07-21 | 开发文档全量重构 | 基于微内核架构重写，修正全部数据，移除 TV 模块 |
| 2026-07-20 | v0.3.0 编译审查 | 7 个编译错误修复 |
| 2026-07-20 | 模型切换审查 | 15 stale state bug, 9 修复 |
| 2026-07-20 | 闪退根因审查 | 13 问题全修复 |
| 2026-07-19 | Crash 漏洞四审四校 | DataPaths/IO/EventReceiver/HttpClient/状态串扰/!! 全部修复 |
| 2026-07-23 | v0.11.3 全量审校 | ProGuard 规则修正 (kernel 包路径) + !! 清零 + 文件 IO/协程 try/catch 补全 + 文档命令计数修正 (self 14→13, agent 11→12, sys 39, plugin 10→11+auto, skill 4→7, inspector 4→6) + 僵尸目录清理 (agent-loop/agent-mission) |
| 2026-07-24 | v0.12.12 记忆孪生 | 6 BUG 修复 (PluginManager版本/startListener/JSON转义/防火墙/inbox轮询/自动恢复) + 5连击激活 + ACP P2P 配对 + 账本自动同步 · 详见 `docs/lessons.md` |
| 2026-07-24 | v0.13.0 全量审校 | 10 插件捆绑补齐 + 循环检测增强 (连续失败) + 会话去重 + 工具输出完整展示 + Claude Bridge 移除 + hardkey Enter 修复 + versionCode 公式修正 · 全部遗留问题已于 v0.14.0 ~ v0.15.2 解决 |
