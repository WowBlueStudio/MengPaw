# Contributing to MengPaw

## 开发流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feat/amazing-feature`)
3. 提交改动 (`git commit -m 'feat: add amazing feature'`)
4. 推送到分支 (`git push origin feat/amazing-feature`)
5. 创建 Pull Request

## 代码规范

- Kotlin 代码风格遵循官方规范
- 所有 UI 文字优先使用中文
- 每个文件不超过 400 行
- 单元测试覆盖核心逻辑

## AI 辅助开发

本项目全程由 AI 辅助编码。若你也使用 AI 工具贡献：

| 阶段 | 编排工具 | 模型 |
|------|---------|------|
| 早期 (US-001 ~ US-012) | Reasonix | DeepSeek Flash |
| 中期 (架构重构 ~ 至今) | Claude Code | DeepSeek Pro |

推荐配置见 `reasonix.toml`（Reasonix）或 `.claude/` 目录（Claude Code）。

## 模块职责

| 模块 | 职责 |
|------|------|
| mengpaw-kernel | 纯 Kotlin/JVM 微内核，零 Android 依赖，JVM 可测试 |
| mengpaw-core | Android 适配层：Vault 加密存储 / IntegrityGuard / SysExecutor |
| mengpaw-shell | Compose UI + 前台服务 |
| mengpaw-design-system | Arco 设计令牌 + 基础组件 |
