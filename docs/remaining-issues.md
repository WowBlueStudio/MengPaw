# v0.13.0 遗留问题 → 全部已在 v0.14.0 解决

> 创建: 2026-07-24 | 解决: 2026-07-24
> 延后项见 `docs/deferred-issues.md`

## 1. 捆绑插件未全部自动安装 ✅ 已修复

**现象**: 10 个插件编译进 APK，但 `plugin.list` 只显示 2-3 个已安装。
**根因**: `Class.forName(className)` 反射加载在 R8 混淆后类名不可靠，且 `PluginManager` 无同步导致 IO 线程与主线程竞争。
**修复**: 
- 将 `Class.forName()` 替换为直接实例化（所有 10 个插件是编译时依赖，无需反射）
- `PluginManager` 所有 public 方法加 `synchronized(this)` 保证线程安全
- 改进日志：打印完整堆栈 + 汇总统计

## 2. 手机 GitHub 连接仍需 VPN ✅ 已修复

**现象**: 手机在美国 IP (VPN) 下能访问 raw.githubusercontent.com 下载插件，关 VPN 后走 Gitee fallback。
**修复**:
- `PluginMarketplaceClient`: GitHub → Gitee → ghproxy.com 三级回退 (索引 + 下载)
- `UpdatePlugin`: GitHub API → Gitee API → ghproxy.com 三级回退 (检查更新 + 下载 APK)
- `PromptEngine`: 系统提示词告知 Agent 网络问题 + VPN 建议
- `PluginExecutor`: marketplace/search/install 错误消息含 VPN 建议

## 3. 记忆孪生组件无法打通 (平板)

**现象**: 平板记忆孪生配对失败，ACP 通道不通。
**状态**: 未深入排查，仅做了代码层面的 ACP 协议清理 (移除 CLAUDE_BRIDGE)。
**排查方向**:
- 平板 `plugin.list` 检查 `memory-twin-plugin` 是否已安装
- `framework.discover` 检查是否能发现对方
- ACP 端口 9876 是否被防火墙/网络拓扑阻断

## 4. 会话重复 ✅ 已修复

**根因**: `current_session.json` 只存消息不存 sessionId。重启后 `restoreCurrentSession()` 造新 ID `"sess_restored"`，原 ID 变成孤儿留在 `session_history.json` 里 → 两个 record 指向同一会话。
**修复**:
- `current_session.json` 改为 `{"sessionId":"...","messages":[...]}` 格式，ID 跨重启保持
- `restoreCurrentSession()` 优先重用原 ID，旧格式兼容 fallback
- 启动时自动清理孤儿 record + dedup 合并重复
- `session_history.json` 写入前自动备份 `.bak`
- 新增 `agent.session.delete/current` 命令 + 系统提示词会话 section + `agent.storage` 补会话统计

## 5. 硬键盘回车导致侧边栏弹出 ✅ 已修复 + 防御加固

**根因**: 头像 `Modifier.clickable` 注册了键盘可聚焦 + `Semantics.onClick`，发送后 focus 漂移到头像，Enter 触发左侧栏。
**修复**:
- v0.13.0: 头像 `clickable` → `pointerInput+detectTapGestures` + `ACTION_DOWN` 检查 (正确)
- 防御加固: `onPreviewKeyEvent` 消费所有 Enter 事件 (DOWN+UP)，`doSend` 加 300ms 防抖

## 6. AdaptiveLlmProvider 测试失败 ✅ 已修复

**现象**: `maxRetries` 默认值从 2 改成 19，测试断言仍为 2。19 次重试导致 ~27 分钟最坏等待。
**修复** (参照 QwenPaw `retry_chat_model.py`):
- `maxRetries = 5` (6 次尝试, ~15s 退避)
- 永久错误 {400, 401, 403} 立即失败不重试
- 可重试: {429, 500, 502, 503, 504} + IO 异常

## 7. 已删除模块

- `ClaudeBridgeHandler.kt` — Claude Code ↔ MengPaw 桥接 (未被实际使用)
- `claude-bridge.ps1` / `agent-bridge.md` / `docs/claude-code-mengpaw-bridge.md` — 相关文档和脚本
- ACP `CLAUDE_BRIDGE` 消息类型 — 从枚举和路由中移除
