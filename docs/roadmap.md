# MengPaw 开发路线图

> 最后更新: 2026-08-16

| Phase | 状态 | 内容 |
|-------|:----:|------|
| **Phase 1** | ✅ | CLI 引擎、3 内置命名空间 (30 命令)、三层安全拦截、会话管理 (含压缩)、LLM 接口 (含降级链)、Prefix Cache、记忆系统、Skill 系统 |
| **Phase 2** | ✅ | Chat UI、前台服务、插件市场 UI、设置 (12 Provider)、Markdown 渲染、BigBang 分词、R8 瘦身 |
| **Phase 3** | ✅ | 独立浏览器 v0.6.0、BrowserBridge 双向桥、45 操控命令、5 浏览器扩展插件 |
| **Phase 4** | ✅ | 微内核拆分 — kernel (44 文件, 纯 JVM) + core (6 文件, Android 适配)、25 插件生态、12 LLM Provider |
| **Phase 5** | ✅ | 安全加固 (WebView/FsPlugin/NetPlugin/Vault/ACP/Sanitizer)、188 Bug 审计、Agent/UI 层深度修复 |
| **Phase 6** | ✅ | UI 全面重构 — iPad 双栏设置 + 侧栏交互升级 + Per-Agent 模型选择 + Token 统计 + 安全规则 + 设计系统合规 + Loop 模式 + 工作区文件 + 会话修复 |
| **Phase 7** | ⏳ | Device 扩展 — 守护态（哨兵模式）雏形：跨设备 heartbeat 监控 + 离线告警 + NotifyBus 推送；蓝牙/手环传感器桥接；眼镜-摄像头集成；~~跨设备技能索取~~ ✅ 已完成 (v0.40.0 开发中, skill.import 经 fleet 通道) |
| **Phase 8** | ⏳ | 桌面端 MVP — `mengpaw-desktop` 适配层 + Compose Multiplatform UI + 22 插件全复用 + 局域网孪生网格直接互通 |
| **Phase 9** | ⏳ | Agent 感官系统 — 手环（健康信号）+ 眼镜（视觉信号）+ 手机（计算中枢）三路融合；Code 扩展 (QuickJS/Python 沙箱)；守护态完整实现（文件完整性/网络异常/物理空间感知） |
| **Phase 10** | ⏳ | 鸿蒙移植 — kernel + ACP + 插件层复用，鸿蒙分布式设备 API 桥接，ArkUI 重写；在线扩展市场开放 |
