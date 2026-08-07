---
name: pr-review
description: 评审 GitHub Pull Request — 拉取 PR → 机器门禁（编译+测试）→ 九维评审 → 分级输出结论。用户说"评审这个 PR/review PR/看看这个 PR"时执行。
---

> **2026-08-07 已迁移**: 本技能已迁移至 Codex skill `mengpaw-pr-review`（用户级 `~/.codex/skills/mengpaw-pr-review/`），下文为兼容保留，新开发以 Codex skill 为准。

# MengPaw PR 评审流程

> 方法论来源：v0.17.1 两轮全量审查沉淀（九维审查；原 `docs/code-review-9-dimensions.md` 已归档删除，git 历史可溯）。评审结论 = 机器门禁 + 九维审查 + 分级输出，**最终合并不由 Claude 决定，由用户拍板**。

## 0. 前置

- [ ] 用户明确指示评审某个 PR（编号或链接）
- [ ] 工作区干净（`git status` 无未提交变更）

## 1. 拉取与门禁

```bash
gh pr checkout <编号>          # 切换到 PR 分支
./gradlew :mengpaw-kernel:test --console=plain
./gradlew :plugin-concise:testDebugUnitTest :plugin-dev:testDebugUnitTest --console=plain
```

- 编译/测试失败 → 直接输出"需修改"结论 + 失败详情，**不进入代码审查**（机器门禁优先）
- 通过 → 进入九维审查

## 2. 九维审查（每维看什么）

| 维度 | 重点检查 |
|------|---------|
| 1 可维护性 | 空 catch 是否吞错误；单文件是否超 400 行；硬编码是否该进 assets/资源 |
| 2 可读性 | 注释是否中文且不过时；命名与行为是否一致；中英混杂 |
| 3 可扩展性 | 插件命令是否注册 metadata.commands；生命周期 onInstall/onUninstall 是否处理 |
| 4 灵活性 | 是否硬编码 Agent 名/路径；是否引入 Android 依赖到内核层 |
| 5 简洁性 | 重复代码；重复构建配置；死代码 |
| 6 可复用性 | 是否应抽取共享工具（如 InjectionPatterns 模式）；版本号是否与现有冲突 |
| 7 可测试性 | 核心逻辑是否有单元测试；新插件是否有测试 |
| 8 健壮性 | 线程安全（mutableMap 并发）；CancellationException 是否被吞；IO 是否 try/catch；路径穿越；renameTo 先删目标（Windows） |
| 9 兼容性 | API 33+ 移除项（如 capturePicture）；Java 版本一致（17）；Locale；CRLF→LF（.gitattributes） |

## 3. 项目特有红线（MengPaw 硬性规则）

- **SPDX 版权头**：所有 .kt/.kts 必须带（AGPL-3.0-or-later OR LicenseRef-Commercial）——缺失直接拒
- **禁止 `!!` 强制解包** —— 用 `?.let {}` / `?: return`
- **文件 IO 必须 try/catch**
- **API Key 安全区**：不允许任何密钥/令牌进日志、审计输出、或用户可见文本（Sanitizer 检查范围）
- **端口冲突**：新端口声明需查 `Ports.kt`（9876 内核保留、9881 MCP 网关）
- **UI 文案**：走 `Strings.kt` 本地化（中英双语），不硬编码
- **仅依赖 kernel**：插件不得依赖其他插件或其他 Android 模块（plugins/ 同级约束）

## 4. 输出格式

```
## PR 评审结论：#<编号> <标题>

**结论**: 可合并 / 需修改 / 拒绝
**门禁**: kernel 测试 ✓ · 插件测试 ✓/✗

### P0 必修（阻塞合并）
- 问题 + 文件:行 + 后果

### P1 应修（建议合并前处理）
- ...

### P2 可后续
- ...

### 亮点（值得保留的做法）
- ...
```

- 有 P0 → 结论"需修改"；无 P0 有 P1 → "需修改或可合并（用户定）"；全 P2 以下 → "可合并"
- 结束语固定提醒：**"最终是否合并由你拍板：`gh pr merge --squash <编号>`"**

## 5. 合并后

- 若 PR 涉及插件：按 `plugin-dev` skill 的发布流程处理（plugins-v* tag）
- 若涉及版本功能：按 `release` skill 处理
- 在 CHANGELOG 对应版本注明贡献者
