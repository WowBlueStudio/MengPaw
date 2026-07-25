# 延后问题清单

> 从 v0.13.0 全链路审计中识别，延后到后续版本处理。
> 创建: 2026-07-24 | 更新: 2026-07-25

---

## 一、记忆孪生平板无法打通 (#3) ✅ 已在 v0.15.0 解决

**来源**: `docs/remaining-issues.md` #3

**修复方案**: 三层十二问审计 → 14 项全修, 含:
- 手动 IP 添加 (`twin.peer.add`) — mDNS 不可用时的 fallback
- 心跳保活 — 30s HEARTBEAT + 90s 离线检测
- QoS 自适应 — Mobile/Metered 网络自动降频
- 错误消息诊断建议 — 引导检查频段/防火墙/端口
- 协议版本协商 — CapabilityCard.protocolVersion

详见 `LESSONS.md` 2026-07-25 条目。

---

## 二、Plugin 系统 — P1/P2 延后项

以下来自插件系统审计 Plan (`#1`)，P0 已全部修复，P1/P2 视优先级处理。

### 下载进度真实百分比 (P1)

**文件**: `PluginMarketplaceClient.kt` + `PluginViewModel.kt`

当前 `InstallState.Downloading(0f)` 固定为 0%。已在 `tryDownload()` 加了 `onProgress` 回调，`PluginExecutor` 加了 `onDownloadProgress`，`PluginViewModel` 已连线。但 Ktor 3.0.3 的 `readAvailable` API 不兼容导致流式读取未实现，目前只回调 0→100 两次。

**待办**: 调研 Ktor 3.0.3 的流式读取 API，实现真正的字节级进度回调。

### plugins.json 补 checksum/size (P2)

**文件**: `plugins.json`

`MarketplaceEntry` 有 `checksum` 和 `sizeBytes` 字段，代码有 SHA256 校验逻辑，但实际 `plugins.json` 中远程插件未填这两个字段。

**待办**: 发布脚本 `scripts/build-plugins.ps1` 已计算 SHA256，需在生成 `plugins.json` 时填入。

### `--from` 自定义市场 (P2)

**文件**: `PluginExecutor.kt`

`PLUGIN_DEV_GUIDE.md` 文档写了 `plugin.install --from https://xxx.github.io/...` 但代码未实现。

**待办**: 实现 `--from` 参数，允许从第三方 `plugins.json` 安装插件。

---

## 三、会话系统 — 延后项

### UI 删除/压缩确认弹窗 (已完成 ✅)

已在 `HistorySidebar.kt` 用 `AlertDialog` 实现。

### 归档"粒度" (已完成 ✅)

已在 `SessionRecord` 加 `archived` 字段 + `agent.session.archive` 命令 + UI toggle。

### session_history.json 损坏时 Agent 提示 (已完成 ✅)

`agent.sessions` 损坏时返回带备份路径的建议。

---

## 四、QwenPaw 深度参考 — 未完成

以下 QwenPaw 设计尚未移植/参考到 MengPaw：

### Loop 模式增强

MengPaw 已有 Goal/Mission 模式的 RubricGate/GoalTurnGate/GoalBudgetGate，但 QwenPaw 还有：
- **Worker-Verifier 协作**: Mission 模式下，Verifier 评估每个子任务结果 → 不合格则重新分配 Worker
- **自适应步数**: 根据任务复杂度动态调整 maxSteps

**参考文件**: QwenPaw `src/copaw/goal_mode.py`, `src/copaw/mission_mode.py`

### LLM Rate Limiter

QwenPaw 的 `LLMRateLimiter` 包含：
- 信号量并发控制
- 滑动窗口 QPM
- 429 协调暂停 + 随机抖动

MengPaw 当前无 Rate Limiter（Android 单用户场景需求低，但多 Agent 并发时可能打爆 API）。

**参考文件**: QwenPaw `src/copaw/providers/rate_limiter.py`

### MCP/ACP 双向通信

QwenPaw 参考了 Claude Code 的 MCP 协议。MengPaw 有 MCP Server/Client 基础和 ACP 协议，但：
- MCP 工具暴露给外部 LLM 的功能未完成
- ACP 跨设备委派 (TWIN_DELEGATE) 仅实现框架，未充分测试

---

## 五、未来版本规划方向

| 优先级 | 项目 | 预估 |
|:--:|------|:--:|
| **高** | #3 记忆孪生平板排查 | 需平板设备调试 |
| **中** | 下载进度真实百分比 | ~30 行，Ktor API 调研 |
| **中** | plugins.json 补 checksum/size | 发布流程改进 |
| **低** | `--from` 自定义市场 | ~40 行 |
| **低** | Rate Limiter | 多 Agent 并发场景前不需要 |
| **参考** | QwenPaw Worker-Verifier 增强 | ~100 行，需设计 |

---

*文档结束 · 下次会话从这里开始*
