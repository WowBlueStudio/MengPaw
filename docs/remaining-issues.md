# v0.13.0 遗留问题

> 创建: 2026-07-24 | 解决后请删除对应条目

## 1. 捆绑插件未全部自动安装

**现象**: 10 个插件编译进 APK，但 `plugin.list` 只显示 2-3 个已安装。
**原因**: `MainActivity.kt` 的 `bundled` 列表已添加全部 10 个插件，但部分插件的 `Class.forName()` 或 `pm.install()` 可能静默失败。
**排查方向**:
- 查看 `adb logcat -s MengPaw` 中 "Auto-install" 或 "Bundled plugin" 警告
- 可能 R8 混淆导致类名找不到
- 可能插件依赖链问题（某些插件依赖其他插件先安装）

## 2. 手机 GitHub 连接仍需 VPN

**现象**: 手机在美国 IP (VPN) 下能访问 raw.githubusercontent.com 下载插件，关 VPN 后走 Gitee fallback。
**状态**: 插件市场有 Gitee 镜像 fallback，`net-plugin` 已捆绑，但直接访问 GitHub API 仍需 VPN。
**方案**: 可考虑内置 GitHub 代理镜像地址 (ghproxy.com) 到 `net-plugin`。

## 3. 记忆孪生组件无法打通 (平板)

**现象**: 平板记忆孪生配对失败，ACP 通道不通。
**状态**: 未深入排查，仅做了代码层面的 ACP 协议清理 (移除 CLAUDE_BRIDGE)。
**排查方向**:
- 平板 `plugin.list` 检查 `memory-twin-plugin` 是否已安装
- `framework.discover` 检查是否能发现对方
- ACP 端口 9876 是否被防火墙/网络拓扑阻断

## 4. 会话重复 (已修复，待验证)

**修复**: `AgentViewModel.kt` 中 `restoreCurrentSession()` 添加 `currentSessionId = "sess_restored"`。
**验证**: 重启 App 多次后检查会话列表是否仍有重复。

## 5. 硬键盘回车导致侧边栏弹出 (已修复，待验证)

**修复**: 
- `MainScreen.kt` `onPreviewKeyEvent` 添加 `ACTION_DOWN` 检查
- 头像 `clickable` 改为 `pointerInput` + `detectTapGestures`
**验证**: 连接硬键盘后按 Enter 发送消息，观察侧边栏是否还会弹出。

## 6. AdaptiveLlmProvider 测试失败

**现象**: `maxRetries` 默认值从 2 改成 19 (本地未提交)，测试断言仍为 2。
**修复**: 测试断言更新为 19。但 19 次重试可能导致 LLM 调用超时过长。

## 7. 已删除模块

- `ClaudeBridgeHandler.kt` — Claude Code ↔ MengPaw 桥接 (未被实际使用)
- `claude-bridge.ps1` / `agent-bridge.md` / `docs/claude-code-mengpaw-bridge.md` — 相关文档和脚本
- ACP `CLAUDE_BRIDGE` 消息类型 — 从枚举和路由中移除
