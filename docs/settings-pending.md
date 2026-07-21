---
name: settings-pending-items
description: 设置页待处理项 — 全部已解决 (2026-07-21)
metadata:
  type: project
---

# 设置页待处理项 ✅ 全部已解决

> 2026-07-21: 6/6 项已实现并编译通过。

## 1. Token 统计接入埋点 ✅

**文件**: `AdaptiveLlmProvider.kt` + `AgentViewModel.kt`
**方案**: 在 AdaptiveLlmProvider 中解析 API 响应 `usage` 字段 (parseUsage)，存入 `lastUsage` 属性 (LlmProvider 接口新增)。AgentViewModel.submitTask() 的 onStep 回调中读取 lastUsage 并调用 TokenStatsCollector.record()。
- `mengpaw-kernel/.../llm/AdaptiveLlmProvider.kt` — +TokenUsage data class, +parseUsage(), +override lastUsage
- `mengpaw-kernel/.../llm/LlmProvider.kt` — +val lastUsage: TokenUsage? get() = null
- `mengpaw-shell/.../ui/screens/AgentViewModel.kt` — onStep 中记录 token usage

## 2. Loop Mission/Mission+ 插件安装检测 ✅

**文件**: `SettingsScreen.kt`
**方案**: 改用 PluginManager.globalInstance.get() 判断实际安装状态：
- MISSION → plugin-agent-mission
- MISSION_PLUS → plugin-agent-loop
- GOAL → 始终 true (内置)

## 3. 安全规则 Switch 接入 SecurityPolicy ✅

**文件**: `SecurityPolicy.kt` + `PluginManager.kt` + `IntegrityGuard.kt` + `SettingsScreen.kt`
**方案**: 为三个安全类添加 companion object global 开关，SettingsScreen switches 直接控制：
- 内核完整性 → `SecurityPolicy.globalEnabled`
- 插件完整性 → `PluginManager.globalInstance.integrityCheckEnabled`
- 文件完整性 → `IntegrityGuard.globalEnabled`
- SecurityPolicy.isAllowed() / IntegrityGuard.validateCommand() / IntegrityGuard.verify() 检查 global flag

## 4. 工作区文件实时刷新 ✅

**文件**: `MainActivity.kt` + `SettingsScreen.kt`
**方案**: 添加 workspaceVersion 状态变量作为 remember key，提供刷新按钮。FrameworkItemSection 支持空 title 跳过 SectionHeader。

## 5. Icons.Default → Icons.Filled 迁移 ✅

**文件**: `SettingsScreen.kt`
**方案**: 3 处替换: Icons.Default.Add(×2) → Icons.Filled.Add, Icons.Default.Close → Icons.Filled.Close

## 6. 框架/Agent 插件-Tools-Skills 列表动态化 ✅

**文件**: `MainActivity.kt` + `AgentViewModel.kt` + `AgentEngine.kt` + `SettingsScreen.kt`
**方案**:
- pluginItems: PluginManager.globalInstance.listAll() → FrameworkItem 动态列表 (含 BUILTIN/OFFICIAL 分类)
- cliItems: AgentEngine.getActiveNamespaces() → FrameworkItem 命名空间列表
- toolItems: McpServer.listTools() → FrameworkItem
- skillItems: 检查 skill-plugin 是否 ACTIVE，是则列出 commands
- AgentEngine 新增 getActiveNamespaces() + getPluginManager()
- AgentViewModel 新增 activeNamespaces() + activeEngine()
- SettingsScreen 新增 onRefreshWorkspace 参数
